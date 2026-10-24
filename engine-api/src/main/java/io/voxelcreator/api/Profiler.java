package io.voxelcreator.api;

import io.voxelcreator.storage.ChunkPos;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Opt-in bounded CPU timings/gauges. No capture means no timing work in operation hot paths. */
public final class Profiler implements AutoCloseable {
  public enum Kind {
    FRAME,
    QUEUE_WAIT,
    CHUNK_GENERATION,
    FIELD_PREFETCH,
    OPERATION,
    STAGE,
    REGION_DELIVERY,
    PREVIEW,
    MESH_CPU,
    UPLOAD_CPU,
    HEAP_BYTES,
    GC_MILLIS,
    GC_COUNT,
    QUEUE_DEPTH,
    ACTIVE_WORKERS,
    CACHE_BYTES,
    STAGING_BYTES,
    COLLECTION_BYTES,
    UPLOAD_BYTES,
    SCENE_CPU_BYTES,
    SCENE_GPU_BYTES
  }

  public enum Unit {
    NANOSECONDS,
    BYTES,
    COUNT,
    MILLISECONDS
  }

  public enum Outcome {
    SUCCESS,
    FAILED,
    CANCELLED,
    MEASURED
  }

  public enum State {
    RECORDING,
    STOPPED,
    TIME_LIMIT,
    SAMPLE_LIMIT,
    SERIES_LIMIT,
    REPLACED,
    CLOSED
  }

  public record Options(int durationMillis, int maxSamples, int maxSeries, int operationStride) {
    public Options {
      if (durationMillis < 100
          || durationMillis > 60000
          || maxSamples < 64
          || maxSamples > 65536
          || maxSeries < 1
          || maxSeries > 1024
          || operationStride < 1
          || operationStride > 65536)
        throw new IllegalArgumentException(
            "Capture limits: 100..60000 ms, 64..65536 samples, 1..1024 series, stride 1..65536");
    }

    public static Options defaults() {
      return new Options(10000, 8192, 128, 256);
    }
  }

  public record Sample(
      long offsetNanos,
      Kind kind,
      Unit unit,
      long value,
      long contextId,
      UUID projectUuid,
      UUID sourceUuid,
      String label,
      ChunkPos chunk,
      Outcome outcome,
      long threadId,
      String threadName) {}

  public record Series(
      Kind kind,
      Unit unit,
      long contextId,
      UUID projectUuid,
      UUID sourceUuid,
      String label,
      int count,
      long minimum,
      long maximum,
      double mean,
      long p50,
      long p95,
      long p99,
      int failed,
      int cancelled) {}

  public record Snapshot(
      int formatVersion,
      UUID captureId,
      Instant startedAt,
      Options options,
      State state,
      long elapsedNanos,
      List<Sample> samples,
      List<Series> series) {
    public Snapshot {
      samples = List.copyOf(samples);
      series = List.copyOf(series);
    }
  }

  private record Key(
      Kind kind, Unit unit, long contextId, UUID projectUuid, UUID sourceUuid, String label) {}

  private final LongSupplier nanos;
  private final Clock clock;
  private volatile Capture current;
  private boolean closed;

  public Profiler() {
    this(System::nanoTime, Clock.systemUTC());
  }

  Profiler(LongSupplier nanos, Clock clock) {
    this.nanos = Objects.requireNonNull(nanos);
    this.clock = Objects.requireNonNull(clock);
  }

  public synchronized Capture start(Options options) {
    if (closed) throw new IllegalStateException("Profiler closed");
    Objects.requireNonNull(options);
    if (current != null) current.stop(State.REPLACED);
    current = new Capture(options);
    return current;
  }

  public Optional<Capture> current() {
    return Optional.ofNullable(current);
  }

  public void stop() {
    var capture = current;
    if (capture != null) capture.stop(State.STOPPED);
  }

  public synchronized void clear() {
    if (current != null) current.stop(State.REPLACED);
    current = null;
  }

  public Scope scope(long contextId, UUID projectUuid, ChunkPos chunk) {
    var capture = current;
    return capture != null && capture.recording()
        ? new Scope(capture, contextId, projectUuid, chunk)
        : null;
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      if (current != null) current.stop(State.CLOSED);
    }
  }

  public final class Capture {
    private final UUID id = UUID.randomUUID();
    private final Options options;
    private final Instant startedAt = clock.instant();
    private final long start = nanos.getAsLong();
    private final List<Sample> samples;
    private final Set<Key> keys = new HashSet<>();
    private final ThreadLocal<int[]> operationTicks = ThreadLocal.withInitial(() -> new int[1]);
    private volatile State state = State.RECORDING;
    private long stoppedAt;

    private Capture(Options options) {
      this.options = options;
      samples = new ArrayList<>(options.maxSamples);
    }

    private long offset() {
      return Math.max(0, nanos.getAsLong() - start);
    }

    public boolean recording() {
      if (state != State.RECORDING) return false;
      if (offset() >= (long) options.durationMillis * 1_000_000) {
        stop(State.TIME_LIMIT);
        return false;
      }
      return true;
    }

    /**
     * Nonzero relative stamp; zero means no measurement. Works even if the monotonic clock starts
     * at zero.
     */
    private long begin() {
      return recording() ? offset() + 1 : 0;
    }

    private long beginOperation() {
      if (state != State.RECORDING) return 0;
      var tick = operationTicks.get();
      if (++tick[0] < options.operationStride) return 0;
      tick[0] = 0;
      return begin();
    }

    private synchronized void stop(State reason) {
      if (state == State.RECORDING) {
        stoppedAt = Math.min(offset(), (long) options.durationMillis * 1_000_000);
        state = reason;
      }
    }

    private synchronized void record(Sample sample) {
      if (!recording()) return;
      if (sample.value < 0 || sample.offsetNanos < 0)
        throw new IllegalArgumentException("Negative capture measurement");
      var key =
          new Key(
              sample.kind,
              sample.unit,
              sample.contextId,
              sample.projectUuid,
              sample.sourceUuid,
              sample.label);
      if (!keys.contains(key) && keys.size() >= options.maxSeries) {
        stop(State.SERIES_LIMIT);
        return;
      }
      keys.add(key);
      samples.add(sample);
      if (samples.size() >= options.maxSamples) stop(State.SAMPLE_LIMIT);
    }

    public Snapshot snapshot() {
      List<Sample> copy;
      State capturedState;
      long elapsed;
      synchronized (this) {
        recording();
        copy = List.copyOf(samples);
        capturedState = state;
        elapsed = state == State.RECORDING ? offset() : stoppedAt;
      }
      return new Snapshot(1, id, startedAt, options, capturedState, elapsed, copy, summarize(copy));
    }
  }

  public static final class Scope {
    private final Capture capture;
    private final long contextId;
    private final UUID project;
    private final ChunkPos chunk;

    private Scope(Capture capture, long contextId, UUID project, ChunkPos chunk) {
      this.capture = capture;
      this.contextId = contextId;
      this.project = project;
      this.chunk = chunk;
    }

    public long begin() {
      return capture.begin();
    }

    public long beginOperation() {
      return capture.beginOperation();
    }

    public void finish(long stamp, Kind kind, UUID source, String label, Outcome outcome) {
      if (stamp == 0) return;
      long end = capture.offset(), offset = stamp - 1;
      capture.record(
          new Sample(
              offset,
              Objects.requireNonNull(kind),
              Unit.NANOSECONDS,
              Math.max(0, end - offset),
              contextId,
              project,
              source,
              label(label),
              chunk,
              Objects.requireNonNull(outcome),
              Thread.currentThread().threadId(),
              label(Thread.currentThread().getName())));
    }

    public void gauge(Kind kind, Unit unit, long value, String label) {
      if (!capture.recording()) return;
      capture.record(
          new Sample(
              capture.offset(),
              Objects.requireNonNull(kind),
              Objects.requireNonNull(unit),
              value,
              contextId,
              project,
              null,
              label(label),
              chunk,
              Outcome.MEASURED,
              Thread.currentThread().threadId(),
              label(Thread.currentThread().getName())));
    }

    private static String label(String label) {
      String value = Objects.requireNonNullElse(label, "");
      return value.length() <= 256 ? value : value.substring(0, 255) + "…";
    }
  }

  public static List<Series> summarize(List<Sample> samples) {
    var grouped = new LinkedHashMap<Key, List<Sample>>();
    for (var sample : samples) {
      var key =
          new Key(
              sample.kind,
              sample.unit,
              sample.contextId,
              sample.projectUuid,
              sample.sourceUuid,
              sample.label);
      grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(sample);
    }
    var result = new ArrayList<Series>();
    grouped.forEach(
        (key, values) -> {
          long[] ordered = new long[values.size()];
          double sum = 0;
          int failed = 0, cancelled = 0;
          for (int i = 0; i < ordered.length; i++) {
            var value = values.get(i);
            ordered[i] = value.value;
            sum += value.value;
            if (value.outcome == Outcome.FAILED) failed++;
            if (value.outcome == Outcome.CANCELLED) cancelled++;
          }
          Arrays.sort(ordered);
          int n = ordered.length;
          result.add(
              new Series(
                  key.kind,
                  key.unit,
                  key.contextId,
                  key.projectUuid,
                  key.sourceUuid,
                  key.label,
                  n,
                  ordered[0],
                  ordered[n - 1],
                  sum / n,
                  percentile(ordered, .5),
                  percentile(ordered, .95),
                  percentile(ordered, .99),
                  failed,
                  cancelled));
        });
    return List.copyOf(result);
  }

  private static long percentile(long[] values, double p) {
    return values[Math.max(0, (int) Math.ceil(p * values.length) - 1)];
  }
}
