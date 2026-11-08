package io.voxelcreator.editor;

import io.voxelcreator.api.Profiler;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional upload timing/gauges cannot replace work outcomes or observe a successor renderer. */
final class SceneUploadObservation {
  interface Observer {
    long begin();
    void finish(long stamp, Profiler.Outcome outcome);
    void uploaded(long bytes);
    void relocated(long bytes);
  }

  record Counters(long uploaded, long relocated) {
    Counters {
      if (uploaded < 0 || relocated < 0) throw new IllegalArgumentException("Negative upload counters");
    }
    Counters since(Counters before) {
      return new Counters(Math.subtractExact(uploaded, before.uploaded),
          Math.subtractExact(relocated, before.relocated));
    }
  }

  record Result(boolean completed, Throwable failure, Throwable observationFailure) {}

  private boolean busy;
  boolean busy() { return busy; }

  Result run(BooleanSupplier current, Supplier<Observer> factory,
      Supplier<Counters> counters, BooleanSupplier work) {
    Objects.requireNonNull(current);
    Objects.requireNonNull(factory);
    Objects.requireNonNull(counters);
    Objects.requireNonNull(work);
    if (busy) throw new IllegalStateException("Scene upload observation already active");
    busy = true;
    try {
      if (!current.getAsBoolean()) return new Result(false, null, null);
      Observer observer = null;
      long stamp = 0;
      Throwable observation = null, primary = null;
      Counters before = null, delta = null;
      try { observer = factory.get(); }
      catch (RuntimeException | Error failure) { observation = failure; }
      if (observer != null && current.getAsBoolean()) {
        try { stamp = observer.begin(); }
        catch (RuntimeException | Error failure) { if (observation == null) observation = failure; }
      }
      if (stamp != 0 && current.getAsBoolean()) {
        try { before = Objects.requireNonNull(counters.get()); }
        catch (RuntimeException | Error failure) { if (observation == null) observation = failure; }
      }
      boolean completed = false;
      var outcome = Profiler.Outcome.CANCELLED;
      if (current.getAsBoolean()) {
        try {
          completed = work.getAsBoolean() && current.getAsBoolean();
          outcome = completed ? Profiler.Outcome.SUCCESS : Profiler.Outcome.CANCELLED;
        } catch (RuntimeException | Error failure) {
          primary = failure;
          outcome = Profiler.Outcome.FAILED;
        }
      }
      // Capture both numbers before any timing/gauge callback can reactivate the renderer.
      if (stamp != 0 && before != null && current.getAsBoolean()) {
        try {
          var after = Objects.requireNonNull(counters.get());
          if (current.getAsBoolean()) delta = after.since(before);
        } catch (RuntimeException | Error failure) { if (observation == null) observation = failure; }
      }
      if (stamp != 0) {
        try { observer.finish(stamp, outcome); }
        catch (RuntimeException | Error failure) { if (observation == null) observation = failure; }
        if (delta != null && current.getAsBoolean()) {
          try { observer.uploaded(delta.uploaded()); }
          catch (RuntimeException | Error failure) { if (observation == null) observation = failure; }
        }
        if (delta != null && current.getAsBoolean()) {
          try { observer.relocated(delta.relocated()); }
          catch (RuntimeException | Error failure) { if (observation == null) observation = failure; }
        }
      }
      return new Result(completed && current.getAsBoolean(), primary, observation);
    } finally {
      busy = false;
    }
  }

  static Observer profiler(Profiler.Scope scope) {
    if (scope == null) return null;
    return new Observer() {
      public long begin() { return scope.begin(); }
      public void finish(long stamp, Profiler.Outcome outcome) {
        scope.finish(stamp, Profiler.Kind.UPLOAD_CPU, null,
            "Streaming upload and arena maintenance", outcome);
      }
      public void uploaded(long bytes) {
        scope.gauge(Profiler.Kind.UPLOAD_BYTES, Profiler.Unit.BYTES, bytes,
            "Streaming upload bytes per frame");
      }
      public void relocated(long bytes) {
        scope.gauge(Profiler.Kind.UPLOAD_BYTES, Profiler.Unit.BYTES, bytes,
            "Arena relocation bytes per frame");
      }
    };
  }

  /** Called after display/fallback settlement; notification faults cannot alter the saved result. */
  static void report(Throwable failure, BooleanSupplier current, Consumer<String> destination) {
    try { PreviewNotifications.report(failure, current, destination); }
    catch (RuntimeException | Error ignored) { /* Observation reporting is optional too. */ }
  }
}
