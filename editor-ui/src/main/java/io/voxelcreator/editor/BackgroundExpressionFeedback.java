package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Display-only, exact-input parser work; staging and document validation remain separate. */
final class BackgroundExpressionFeedback implements AutoCloseable {
  static final long QUIET_NANOS = 150_000_000L, CACHE_BYTES = 2L << 20;

  enum Phase {
    IDLE,
    WAITING,
    RUNNING,
    READY,
    FAILED,
    STALE,
    CLOSED
  }

  record View(Phase phase, ExpressionFeedback.Result result, String message, boolean retryable) {}

  private record Owner(
      ProjectData project,
      Object lifetime,
      UUID projectId,
      long revision,
      Noise noise,
      UUID noiseId,
      Item item,
      UUID itemId,
      String kind,
      List<UUID> selection) {
    static Owner capture(EditorState s, Noise n, Item i) {
      return new Owner(
          s.project(),
          s.document.sessionToken(),
          s.project().project.uuid,
          s.revision,
          n,
          n.uuid,
          i,
          i.uuid,
          s.selectedKind,
          List.copyOf(s.multiSelection));
    }

    boolean current(EditorState s) {
      return s.project() == project
          && s.document.sessionToken() == lifetime
          && Objects.equals(projectId, s.project().project.uuid)
          && s.revision == revision
          && s.draft == noise
          && Objects.equals(noiseId, noise.uuid)
          && Objects.equals(noiseId, s.selected)
          && noise.processing.contains(item)
          && Objects.equals(itemId, item.uuid)
          && "EXPRESSION".equals(item.kind)
          && Objects.equals(kind, s.selectedKind)
          && selection.equals(List.copyOf(s.multiSelection));
    }
  }

  private record Prepared(
      Owner owner, ExpressionFeedback.Request input, ExpressionFeedback.Result result,
      Throwable failure) {}

  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean delivered, admitted;
  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final LongSupplier clock;
  private final Supplier<String> source;
  private final Function<ExpressionFeedback.Request, ExpressionFeedback.Result> inspect;
  private final LinkedHashMap<ExpressionFeedback.Request, ExpressionFeedback.Result> cache =
      new LinkedHashMap<>(16, .75f, true);
  private long cacheBytes, changedAt, launches, version;
  private Owner owner;
  private ExpressionFeedback.Request desired;
  private ExpressionFeedback.Result result;
  private Phase phase = Phase.IDLE;
  private String message = "";
  private FutureTask<Void> task;
  private Object token = new Object();
  private Object adopting;

  BackgroundExpressionFeedback(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      LongSupplier clock,
      Supplier<String> source) {
    this(state, executor, ui, clock, source, ExpressionFeedback::inspectCaptured);
  }

  BackgroundExpressionFeedback(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      LongSupplier clock,
      Supplier<String> source,
      Function<ExpressionFeedback.Request, ExpressionFeedback.Result> inspect) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.clock = Objects.requireNonNull(clock);
    this.source = source;
    this.inspect = Objects.requireNonNull(inspect);
  }

  private String text(Item item) {
    return source == null ? ExpressionFeedback.source(item) : source.get();
  }

  private boolean currentWork(Object request) {
    return request == token && phase != Phase.CLOSED;
  }

  private View snapshot() {
    return new View(phase, phase == Phase.READY ? result : null, message, phase == Phase.FAILED);
  }

  View observe(Noise noise, Item item) {
    if (phase == Phase.CLOSED) return snapshot();
    Object request = token;
    try {
      var current = Owner.capture(state, noise, item);
      boolean contextChanged = !current.equals(owner);
      if (contextChanged) {
        retire();
        request = token;
        owner = current;
        desired = null;
        cache.clear();
        cacheBytes = 0;
      }
      if (!current.current(state)) {
        stale();
        return snapshot();
      }
      String currentText = text(item);
      if (!currentWork(request)) return snapshot();
      var input = ExpressionFeedback.capture(state.project(), noise, item, currentText);
      if (!input.equals(desired) || phase == Phase.STALE) {
        retire();
        request = token;
        desired = input;
        result = cache.get(input);
        if (result != null) {
          phase = Phase.READY;
          message = "";
        } else {
          phase = Phase.WAITING;
          long now = clock.getAsLong();
          if (!currentWork(request)) return snapshot();
          changedAt = now - (contextChanged ? QUIET_NANOS : 0);
          message = "Waiting for typing to pause before checking the expression.";
        }
      }
      refresh();
      if (!currentWork(request)) return snapshot();
      if (phase == Phase.WAITING) {
        long now = clock.getAsLong();
        if (!currentWork(request)) return snapshot();
        if (now - changedAt >= QUIET_NANOS) start();
      }
    } catch (Throwable failure) {
      fail(request, failure);
    }
    return snapshot();
  }

  View view() {
    refresh();
    return snapshot();
  }

  private void start() {
    var input = desired;
    var captured = owner;
    Object request = token;
    long handed = delivery;
    phase = Phase.RUNNING;
    message = "Checking expression feedback in the background...";
    launches++;
    try {
      task =
          new FutureTask<>(
              () -> {
                ExpressionFeedback.Result value = null;
                Throwable failure = null;
                try {
                  value = Objects.requireNonNull(inspect.apply(input));
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Prepared(captured, input, value, failure)))
                  return null;
                ui.accept(() -> delivered(request, handed));
                return null;
              });
      executor.execute(task);
      if (owns(request)) admitted = true;
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  private boolean owns(Object request) {
    return request == token && phase == Phase.RUNNING && task != null;
  }

  private void delivered(Object request, long handed) {
    if (owns(request) && handed == delivery) delivered = true;
  }

  private void publish(Object request) {
    if (!owns(request) || adopting == request) return;
    Object outer = adopting;
    adopting = request;
    var prepared = handoff.take();
    try {
      if (prepared == null || prepared.owner() != owner || !prepared.input().equals(desired))
        throw new IllegalStateException("Expression feedback was not delivered");
      var captured = prepared.owner();
      var input = prepared.input();
      String currentText = text(captured.item());
      if (!owns(request)) return;
      if (!captured.current(state)
          || !input.equals(
              ExpressionFeedback.capture(
                  state.project(), captured.noise(), captured.item(), currentText))) {
        stale();
        return;
      }
      if (prepared.failure() != null) {
        fail(request, prepared.failure());
        return;
      }
      task = null;
      retire();
      result = prepared.result();
      phase = Phase.READY;
      message = "";
      if (input.bytes() <= CACHE_BYTES) {
        var previous = cache.put(input, result);
        if (previous == null) cacheBytes += input.bytes();
        while (cache.size() > 128 || cacheBytes > CACHE_BYTES) {
          var first = cache.keySet().iterator().next();
          cache.remove(first);
          cacheBytes -= first.bytes();
        }
      }
    } catch (Throwable rejected) {
      fail(request, rejected);
    } finally {
      adopting = outer;
    }
  }

  private void refresh() {
    if (phase == Phase.CLOSED || owner == null) return;
    Object request = token;
    try {
      if (!owner.current(state)) {
        stale();
        return;
      }
      if (task != null) {
        var done = BackgroundFailure.completed(task);
        if (done != null && done.failure() != null) fail(request, done.failure());
        else if (done != null && admitted && delivered) publish(request);
      }
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  void retry() {
    if (phase != Phase.FAILED || owner == null || desired == null) return;
    Object request = token;
    var captured = owner;
    var input = desired;
    try {
      String currentText = text(captured.item());
      if (!currentWork(request)) return;
      if (!captured.current(state)
          || !input.equals(
              ExpressionFeedback.capture(
                  state.project(), captured.noise(), captured.item(), currentText))) {
        stale();
        return;
      }
      retire();
      request = token;
      phase = Phase.WAITING;
      long now = clock.getAsLong();
      if (!currentWork(request)) return;
      changedAt = now - QUIET_NANOS;
      message = "Retrying expression feedback.";
      start();
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  void supersede() {
    if (phase == Phase.CLOSED) return;
    retire();
    Object request = token;
    desired = null;
    phase = Phase.WAITING;
    try {
      long now = clock.getAsLong();
      if (!currentWork(request)) return;
      changedAt = now;
      message = "Waiting for typing to pause before checking the expression.";
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  void suspend() {
    if (phase == Phase.CLOSED || phase == Phase.IDLE && owner == null) return;
    retire();
    owner = null;
    desired = null;
    cache.clear();
    cacheBytes = 0;
    phase = Phase.IDLE;
    message = "";
  }

  private void stale() {
    if (phase == Phase.STALE) return;
    retire();
    phase = Phase.STALE;
    message = "Expression feedback became stale; check the current source again.";
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    retire();
    phase = Phase.FAILED;
    message =
        "Expression feedback unavailable: "
            + BackgroundFailure.describe(failure)
            + ". Retry checking explicitly.";
    try {
      state.error("EXPRESSION_FEEDBACK_FAILED", EditorEvents.Subsystem.APPLICATION, message);
    } catch (Throwable ignored) {
      /* Display-only failure must leave editing/staging/cancellation available. */
    }
  }

  private void retire() {
    var previous = task;
    token = new Object();
    version++;
    task = null;
    delivered = admitted = false;
    handoff.advance(++delivery);
    result = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  long launches() {
    return launches;
  }

  Object selectionOwner() {
    return token;
  }

  long version() {
    return version;
  }

  int cachedEntries() {
    return cache.size();
  }

  long cachedBytes() {
    return cacheBytes;
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    retire();
    owner = null;
    desired = null;
    cache.clear();
    cacheBytes = 0;
    handoff.close();
    phase = Phase.CLOSED;
    message = "";
  }
}
