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
  private boolean enabled, checked, busy;
  private long generation;
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
    if (busy || state == State.CLOSED) return;
    busy = true;
    try { synchronizeCapture(scope); }
    catch (RuntimeException | Error failure) { fail(failure); }
    finally { busy = false; }
  }

  private void synchronizeCapture(Profiler.Scope scope) {
    long opening = generation;
    boolean recording = scope != null && scope.recording();
    if (!current(opening)) return;
    if (!recording) {
      boolean release = enabled;
      enabled = false;
      if (state != State.FAILED && state != State.UNAVAILABLE) {
        state = State.IDLE;
        detail = "Capture off; unfinished GPU measurements discarded";
      }
      if (release) release();
      return;
    }
    if (!scope.captureId().equals(owner)) {
      owner = scope.captureId();
      checked = false;
      bits = 0;
      submitted = recorded = skipped = discarded = last = 0;
      state = State.IDLE;
      detail = "Waiting for a 3D viewport draw";
      enabled = true;
      release(false);
      return;
    }
    enabled = true;
  }

  /** Zero means render normally without a timer (disabled, unsupported, nested, or ring full). */
  public long begin(Profiler.Scope scope, String label) {
    if (busy || state == State.CLOSED) return 0;
    busy = true;
    try {
      synchronizeCapture(scope);
      if (!enabled || state == State.CLOSED || state == State.FAILED || state == State.UNAVAILABLE)
        return 0;
      long opening = generation;
      if (!checked) {
        int width = backend.counterBits();
        if (!current(opening)) return 0;
        bits = width;
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
      if (!current(opening) || stamp == 0) return 0;
      if (slot.start == 0) {
        int name = create(opening);
        if (!current(opening)) return 0;
        slot.start = name;
      }
      if (slot.end == 0) {
        int name = create(opening);
        if (!current(opening)) return 0;
        slot.end = name;
      }
      slot.scope = scope;
      slot.stamp = stamp;
      slot.label = bounded(label);
      slot.sequence = ++sequence;
      long issued = nanos.getAsLong();
      if (!current(opening)) return 0;
      slot.issued = issued;
      slot.ended = false;
      active = slot;
      backend.timestamp(slot.start);
      if (!current(opening)) return 0;
      submitted++;
      return slot.sequence;
    } catch (RuntimeException | Error failure) {
      fail(failure);
      return 0;
    } finally {
      busy = false;
    }
  }

  private int create(long opening) {
    int query = backend.create();
    if (!current(opening)) {
      retire(query, 0);
      return 0;
    }
    if (query == 0) throw new IllegalStateException("GPU query allocation failed");
    return query;
  }

  public void end(long token, boolean success) {
    if (busy || state == State.CLOSED || token == 0 || active == null || token != active.sequence) return;
    busy = true;
    long opening = generation;
    var slot = active;
    try {
      backend.timestamp(slot.end);
      if (!current(opening)) return;
      slot.outcome = success ? Profiler.Outcome.MEASURED : Profiler.Outcome.FAILED;
      slot.ended = true;
      active = null;
    } catch (RuntimeException | Error failure) {
      fail(failure);
    } finally {
      busy = false;
    }
  }

  /** Each pending pair is checked at most once; unavailable query results are never read. */
  public List<Measurement> poll(Profiler.Scope current) {
    if (busy || state == State.CLOSED) return List.of();
    busy = true;
    var results = new ArrayList<Measurement>(CAPACITY);
    try {
      synchronizeCapture(current);
      if (!enabled || state != State.READY) return List.of();
      long opening = generation;
      long now = nanos.getAsLong();
      if (!current(opening)) return List.of();
      long maximum = maximumAge();
      for (var slot : slots) {
        if (slot.scope == null || !slot.ended) continue;
        long age = now - slot.issued;
        if (age < 0 || age >= maximum) {
          discard(slot);
          if (!current(opening)) return List.of();
          continue;
        }
        boolean startReady = backend.available(slot.start);
        if (!current(opening)) return List.of();
        if (!startReady) continue;
        boolean endReady = backend.available(slot.end);
        if (!current(opening)) return List.of();
        if (!endReady) continue;
        long start = backend.result(slot.start);
        if (!current(opening)) return List.of();
        long end = backend.result(slot.end);
        if (!current(opening)) return List.of();
        long elapsed = end - start;
        if (bits < 64) elapsed &= (1L << bits) - 1;
        if (elapsed < 0 || elapsed >= maximum) {
          discard(slot);
          if (!current(opening)) return List.of();
          continue;
        }
        var scope = slot.scope;
        long stamp = slot.stamp, token = slot.sequence;
        String label = slot.label;
        var outcome = slot.outcome;
        var capture = owner;
        int width = bits;
        clear(slot);
        boolean accepted = scope.recordDuration(stamp, Profiler.Kind.GPU_DRAW, null, label, elapsed, outcome);
        if (!current(opening)) return List.of();
        results.add(
            new Measurement(
                token,
                capture,
                stamp - 1,
                label,
                width,
                Long.toUnsignedString(start, 16),
                Long.toUnsignedString(end, 16),
                elapsed,
                accepted));
        if (accepted) {
          recorded++;
          last = elapsed;
        } else discarded++;
      }
    } catch (RuntimeException | Error failure) {
      fail(failure);
    } finally {
      busy = false;
    }
    return state == State.CLOSED ? List.of() : List.copyOf(results);
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
    retire(start, end);
  }

  private static void clear(Slot slot) {
    slot.scope = null;
    slot.label = null;
    slot.stamp = slot.issued = slot.sequence = 0;
    slot.ended = false;
    slot.outcome = null;
  }

  private void retire(int start, int end) {
    new RenderRetirement(h -> backend.delete(h.id()), new RenderRetirement.Handle[] {
      new RenderRetirement.Handle(RenderRetirement.Kind.QUERY, start),
      new RenderRetirement.Handle(RenderRetirement.Kind.QUERY, end)
    }).run();
  }

  private void release() {
    release(true);
  }

  private void release(boolean countDiscarded) {
    generation++;
    active = null;
    var handles = new ArrayList<RenderRetirement.Handle>(2 * CAPACITY);
    for (var slot : slots) {
      if (countDiscarded && slot.scope != null) discarded++;
      int start = slot.start, end = slot.end;
      slot.start = slot.end = 0;
      clear(slot);
      handles.add(new RenderRetirement.Handle(RenderRetirement.Kind.QUERY, start));
      handles.add(new RenderRetirement.Handle(RenderRetirement.Kind.QUERY, end));
    }
    new RenderRetirement(h -> backend.delete(h.id()), handles.toArray(RenderRetirement.Handle[]::new)).run();
  }

  private boolean current(long opening) { return state != State.CLOSED && generation == opening; }

  private void fail(Throwable failure) {
    if (state != State.CLOSED) {
      enabled = false;
      state = State.FAILED;
      detail = "GPU measurement suspended until the next capture";
      try { release(); }
      catch (RuntimeException | Error ignored) { /* The original work failure retains priority. */ }
    }
    if (failure instanceof Error error) throw error;
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
    enabled = false;
    state = State.CLOSED;
    detail = "GPU queries closed";
    release();
  }
}
