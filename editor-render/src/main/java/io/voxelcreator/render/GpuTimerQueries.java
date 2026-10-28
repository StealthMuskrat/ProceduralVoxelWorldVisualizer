package io.voxelcreator.render;

import io.voxelcreator.api.Profiler;
import java.util.*;
import java.util.function.LongSupplier;

/** Render-thread-owned timestamp pairs. Fixed capacity; availability polling never waits. */
public final class GpuTimerQueries implements AutoCloseable {
  public static final int CAPACITY = 4;
  public static final long MAX_AGE_NANOS = 2_000_000_000L;

  public interface Backend {
    int counterBits();

    int create();

    void timestamp(int query);

    boolean available(int query);

    long result(int query);

    void delete(int query);
  }

  public enum State {
    IDLE,
    READY,
    UNAVAILABLE,
    FAILED,
    CLOSED
  }

  public record Statistics(
      State state,
      int counterBits,
      int allocatedQueries,
      int pending,
      long submitted,
      long recorded,
      long skipped,
      long discarded,
      long lastNanos,
      String detail) {}

  public record Measurement(
      long sequence,
      UUID captureId,
      long offsetNanos,
      String label,
      int counterBits,
      String startHex,
      String endHex,
      long elapsedNanos,
      boolean recorded) {}

  private static final class Slot {
    int start, end;
    long sequence, issued, stamp;
    boolean ended;
    Profiler.Scope scope;
    Profiler.Outcome outcome;
    String label;
  }

  private final Backend backend;
  private final LongSupplier nanos;
  private final Slot[] slots = new Slot[CAPACITY];
  private UUID owner;
  private Slot active;
  private State state = State.IDLE;
  private int bits;
  private boolean enabled, checked;
  private long sequence, submitted, recorded, skipped, discarded, last;
  private String detail = "Start a capture to measure GPU draws";

  public GpuTimerQueries(Backend backend) {
    this(backend, System::nanoTime);
  }

  public GpuTimerQueries(Backend backend, LongSupplier nanos) {
    this.backend = Objects.requireNonNull(backend);
    this.nanos = Objects.requireNonNull(nanos);
    Arrays.setAll(slots, i -> new Slot());
  }

  public static Statistics idle() {
    return new Statistics(
        State.IDLE, 0, 0, 0, 0, 0, 0, 0, 0, "Start a capture to measure GPU draws");
  }

  /** Old captures are reclaimed before any result can enter a replacement capture. */
  public void synchronize(Profiler.Scope scope) {
    if (state == State.CLOSED) return;
    if (scope == null || !scope.recording()) {
      if (enabled) release();
      enabled = false;
      if (state != State.FAILED && state != State.UNAVAILABLE) {
        state = State.IDLE;
        detail = "Capture off; unfinished GPU measurements discarded";
      }
      return;
    }
    if (!scope.captureId().equals(owner)) {
      release();
      owner = scope.captureId();
      checked = false;
      bits = 0;
      submitted = recorded = skipped = discarded = last = 0;
      state = State.IDLE;
      detail = "Waiting for a 3D viewport draw";
    }
    enabled = true;
  }

  /** Zero means render normally without a timer (disabled, unsupported, nested, or ring full). */
  public long begin(Profiler.Scope scope, String label) {
    synchronize(scope);
    if (!enabled || state == State.CLOSED || state == State.FAILED || state == State.UNAVAILABLE)
      return 0;
    try {
      if (!checked) {
        bits = backend.counterBits();
        checked = true;
      }
      if (bits < 30 || bits > 64) {
        state = State.UNAVAILABLE;
        detail = "GPU timestamp queries unavailable in this context";
        return 0;
      }
      state = State.READY;
      detail = "Asynchronous GPU timestamp queries";
      if (active != null) {
        skipped++;
        return 0;
      }
      Slot slot = null;
      for (var candidate : slots)
        if (candidate.scope == null) {
          slot = candidate;
          break;
        }
      if (slot == null) {
        skipped++;
        return 0;
      }
      long stamp = scope.begin();
      if (stamp == 0) return 0;
      if (slot.start == 0) slot.start = create();
      if (slot.end == 0) slot.end = create();
      slot.scope = scope;
      slot.stamp = stamp;
      slot.label = bounded(label);
      slot.sequence = ++sequence;
      slot.issued = nanos.getAsLong();
      slot.ended = false;
      active = slot;
      backend.timestamp(slot.start);
      submitted++;
      return slot.sequence;
    } catch (RuntimeException failure) {
      fail(failure);
      return 0;
    }
  }

  private int create() {
    int query = backend.create();
    if (query == 0) throw new IllegalStateException("GPU query allocation failed");
    return query;
  }

  public void end(long token, boolean success) {
    if (token == 0 || active == null || token != active.sequence) return;
    try {
      backend.timestamp(active.end);
      active.outcome = success ? Profiler.Outcome.MEASURED : Profiler.Outcome.FAILED;
      active.ended = true;
      active = null;
    } catch (RuntimeException failure) {
      fail(failure);
    }
  }

  /** Each pending pair is checked at most once; unavailable query results are never read. */
  public List<Measurement> poll(Profiler.Scope current) {
    synchronize(current);
    if (!enabled || state != State.READY) return List.of();
    var results = new ArrayList<Measurement>(CAPACITY);
    try {
      long now = nanos.getAsLong(), maximum = maximumAge();
      for (var slot : slots) {
        if (slot.scope == null || !slot.ended) continue;
        long age = now - slot.issued;
        if (age < 0 || age >= maximum) {
          discard(slot);
          continue;
        }
        if (!backend.available(slot.start) || !backend.available(slot.end)) continue;
        long start = backend.result(slot.start), end = backend.result(slot.end);
        long elapsed = end - start;
        if (bits < 64) elapsed &= (1L << bits) - 1;
        if (elapsed < 0 || elapsed >= maximum) {
          discard(slot);
          continue;
        }
        boolean accepted =
            slot.scope.recordDuration(
                slot.stamp, Profiler.Kind.GPU_DRAW, null, slot.label, elapsed, slot.outcome);
        results.add(
            new Measurement(
                slot.sequence,
                owner,
                slot.stamp - 1,
                slot.label,
                bits,
                Long.toUnsignedString(start, 16),
                Long.toUnsignedString(end, 16),
                elapsed,
                accepted));
        if (accepted) {
          recorded++;
          last = elapsed;
        } else discarded++;
        clear(slot);
      }
    } catch (RuntimeException failure) {
      fail(failure);
    }
    return List.copyOf(results);
  }

  private long maximumAge() {
    return bits < 31 ? Math.min(MAX_AGE_NANOS, 1L << bits) : MAX_AGE_NANOS;
  }

  private void discard(Slot slot) {
    discarded++;
    // Reusing an unfinished query name would overwrite its old measurement. Retire both names.
    int start = slot.start, end = slot.end;
    slot.start = slot.end = 0;
    clear(slot);
    RuntimeException failure = null;
    try {
      delete(start);
    } catch (RuntimeException problem) {
      failure = problem;
    }
    try {
      delete(end);
    } catch (RuntimeException problem) {
      if (failure == null) failure = problem;
    }
    if (failure != null) throw failure;
  }

  private static void clear(Slot slot) {
    slot.scope = null;
    slot.label = null;
    slot.stamp = slot.issued = slot.sequence = 0;
    slot.ended = false;
    slot.outcome = null;
  }

  private void delete(int query) {
    if (query != 0) backend.delete(query);
  }

  private void release() {
    active = null;
    for (var slot : slots) {
      if (slot.scope != null) discarded++;
      int start = slot.start, end = slot.end;
      slot.start = slot.end = 0;
      clear(slot);
      try {
        delete(start);
      } catch (RuntimeException ignored) {
        /* Context teardown owns failure. */
      }
      try {
        delete(end);
      } catch (RuntimeException ignored) {
        /* Try every other owned name too. */
      }
    }
  }

  private void fail(RuntimeException failure) {
    release();
    state = State.FAILED;
    detail = "GPU measurement suspended until the next capture: " + bounded(failure.getMessage());
  }

  public Statistics statistics() {
    int names = 0, pending = 0;
    for (var slot : slots) {
      if (slot.start != 0) names++;
      if (slot.end != 0) names++;
      if (slot.scope != null) pending++;
    }
    return new Statistics(
        state, bits, names, pending, submitted, recorded, skipped, discarded, last, detail);
  }

  private static String bounded(String label) {
    var value = Objects.requireNonNullElse(label, "");
    return value.length() <= 256 ? value : value.substring(0, 255) + "…";
  }

  @Override
  public void close() {
    if (state == State.CLOSED) return;
    release();
    enabled = false;
    state = State.CLOSED;
    detail = "GPU queries closed";
  }
}
