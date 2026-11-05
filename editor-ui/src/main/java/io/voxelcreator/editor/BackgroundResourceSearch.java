package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One committed index and one coalesced query; workers never read the live document. */
final class BackgroundResourceSearch implements AutoCloseable {
  private enum Phase {
    IDLE, WAITING, BUILDING, SEARCHING, READY, FAILED, CLOSED
  }

  private record Source(
      ProjectData project, Object lifetime, UUID id, long revision, JsonNode values) {
    static Source capture(EditorState s) {
      return new Source(
          s.project(), s.document.sessionToken(), s.project().project.uuid,
          s.revision, ProjectIO.JSON.valueToTree(s.project()));
    }
    boolean current(EditorState s) {
      return project == s.project() && lifetime == s.document.sessionToken()
          && Objects.equals(id, s.project().project.uuid) && revision == s.revision;
    }
    boolean valuesCurrent(EditorState s) {
      return current(s) && values.equals(ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  private record Outcome(
      Source source,
      ResourceSearchIndex index,
      ResourceSearchIndex.Filter filter,
      List<ResourceSearchIndex.Hit> hits, Throwable failure) implements AutoCloseable {
    @Override
    public void close() {
      // CPU values are released with their owned mailbox slot.
    }
  }

  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Supplier<ResourceSearchIndex.Filter> liveFilter;
  private final Function<ProjectData, ResourceSearchIndex> build;
  private final BiFunction<ResourceSearchIndex, ResourceSearchIndex.Filter,
      List<ResourceSearchIndex.Hit>> search;
  private final OwnedPreviewMailbox<Outcome> handoff = new OwnedPreviewMailbox<>();
  private Source source, acceptedSource, failedOwner;
  private ResourceSearchIndex.Filter observed, failedFilter;
  private ResourceSearchIndex index;
  private ResourceSearchIndex.Filter desired, accepted;
  private List<ResourceSearchIndex.Hit> results = List.of();
  private Phase phase = Phase.IDLE;
  private Object token = new Object();
  private Object adopting;
  private FutureTask<Void> task;
  private boolean delayed, desiredImmediate, delivered, admitted;
  private long changedAt, builds, queries, version;
  private String message = "";

  BackgroundResourceSearch(
      EditorState state, Executor executor, Consumer<Runnable> ui,
      Supplier<ResourceSearchIndex.Filter> liveFilter) {
    this(state, executor, ui, liveFilter, ResourceSearchIndex::new, ResourceSearchIndex::search);
  }

  BackgroundResourceSearch(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      Supplier<ResourceSearchIndex.Filter> liveFilter,
      Function<ProjectData, ResourceSearchIndex> build,
      BiFunction<ResourceSearchIndex, ResourceSearchIndex.Filter, List<ResourceSearchIndex.Hit>> search) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.liveFilter = Objects.requireNonNull(liveFilter);
    this.build = Objects.requireNonNull(build);
    this.search = Objects.requireNonNull(search);
  }

  List<ResourceSearchIndex.Hit> observe(ProjectData project, long revision,
      ResourceSearchIndex.Filter request, long now, boolean immediate) {
    if (phase == Phase.CLOSED) return List.of();
    observed = request;
    try {
      Objects.requireNonNull(request);
      if (phase == Phase.FAILED && failedOwner != null && failedOwner.current(state)
          && request.equals(failedFilter)) return results;
      if (project != state.project() || revision != state.revision)
        throw new IllegalArgumentException("Find requires the current committed document");
      if (source == null || !source.current(state)) {
        retire();
        source = Source.capture(state);
        index = null;
        desired = null;
        phase = Phase.IDLE;
        immediate = true;
      }
      if (!request.equals(desired)) {
        var previous = desired == null ? accepted : desired;
        desiredImmediate = immediate || previous == null || request.query().isEmpty()
            || !ResourceSearchSession.sameScope(previous, request);
        delayed = !desiredImmediate && index != null && index.size() >= ResourceSearchSession.DEBOUNCE_ROWS
            && !request.query().isEmpty() && previous != null
            && ResourceSearchSession.sameScope(previous, request);
        changedAt = now;
        desired = request;
        version++;
        // Keep a source build through typing; its index can serve the latest query on completion.
        if (phase != Phase.BUILDING) {
          retire();
          phase = Phase.WAITING;
        }
      } else if (immediate && phase == Phase.WAITING) {
        desiredImmediate = true;
        delayed = false;
      }
      var capturedSource = source;
      if (!refresh() || source != capturedSource || !request.equals(desired)) return results;
      if (phase == Phase.FAILED || phase == Phase.CLOSED) return results;
      if (acceptedSource == source && request.equals(accepted)) {
        if (phase != Phase.READY) retire();
        phase = Phase.READY;
        message = "";
        return results;
      }
      if (phase == Phase.BUILDING || phase == Phase.SEARCHING) return results;
      if (index == null) start();
      else {
        long elapsed = now - changedAt;
        if (elapsed < 0) changedAt = now;
        if (delayed && (elapsed < 0 || elapsed < ResourceSearchSession.DEBOUNCE_NANOS)) {
          phase = Phase.WAITING;
          message = "Waiting for typing to pause before searching committed resources.";
        } else start();
      }
    } catch (Throwable failure) {
      fail(token, failure);
    }
    return results;
  }

  private void start() {
    retire();
    Object request = token;
    var owner = source;
    var filter = desired;
    var existing = index;
    long delivery = handoff.begin();
    phase = existing == null ? Phase.BUILDING : Phase.SEARCHING;
    message = existing == null ? "Building the committed Find index in the background..."
        : "Searching committed resources in the background...";
    if (existing == null) builds++;
    queries++;
    try {
      task =
          new FutureTask<>(
              () -> {
                Outcome outcome;
                try {
                  var ready =
                      existing == null
                          ? Objects.requireNonNull(
                              build.apply(ProjectIO.JSON.convertValue(owner.values(), ProjectData.class)))
                          : existing;
                  var hits = List.copyOf(Objects.requireNonNull(search.apply(ready, filter)));
                  outcome = new Outcome(owner, ready, filter, hits, null);
                } catch (Throwable rejected) {
                  outcome = new Outcome(owner, null, filter, List.of(), rejected);
                }
                if (!handoff.publish(delivery, outcome)) return null;
                ui.accept(() -> delivered(request, delivery));
                return null;
              });
      executor.execute(task);
      if (owns(request)) admitted = true;
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  private boolean owns(Object request) {
    return request == token && (phase == Phase.BUILDING || phase == Phase.SEARCHING);
  }

  private void delivered(Object request, long delivery) {
    if (owns(request) && handoff.current(delivery)) delivered = true;
  }

  private boolean publish(Object request) {
    if (!owns(request) || adopting == request) return false;
    Object outer = adopting;
    adopting = request;
    var captured = source;
    var outcome = handoff.take();
    try {
      if (outcome == null || outcome.source() != captured)
        throw new IllegalStateException("Find result was not delivered");
      var currentFilter = liveFilter.get();
      // A presentation callback may clear/close Find or prepare an entirely new request.
      if (!owns(request) || source != captured) return false;
      if (!captured.valuesCurrent(state)) {
        fail(request, new IllegalStateException("Committed values changed; retry the current search"));
        return false;
      }
      if (outcome.failure() != null) {
        fail(request, outcome.failure());
        return false;
      }
      boolean exact = outcome.filter().equals(desired) && outcome.filter().equals(currentFilter);
      // The complete worker and its UI handoff succeeded before any rows/index are adopted.
      task = null;
      retire();
      index = outcome.index();
      if (exact) {
        results = outcome.hits();
        accepted = outcome.filter();
        acceptedSource = captured;
        phase = Phase.READY;
        message = "";
      } else {
        phase = Phase.WAITING;
        delayed = !desiredImmediate && index.size() >= ResourceSearchSession.DEBOUNCE_ROWS
            && desired != null && !desired.query().isEmpty()
            && ResourceSearchSession.sameScope(outcome.filter(), desired);
        message = "The index is ready; waiting for the current query.";
      }
      return true;
    } catch (Throwable rejected) {
      fail(request, rejected);
      return false;
    } finally {
      adopting = outer;
    }
  }

  private boolean refresh() {
    if (phase != Phase.BUILDING && phase != Phase.SEARCHING) return true;
    Object request = token;
    var captured = source;
    try {
      if (!captured.current(state)) {
        cancelPending();
        return false;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) {
        fail(request, done.failure());
        return false;
      }
      // A callback can run inline inside ui.accept; readiness does not prove the handoff returned.
      return !admitted || !delivered || done == null || publish(request);
    } catch (Throwable failure) {
      fail(request, failure);
      return false;
    }
  }

  boolean pending() {
    refresh();
    boolean current = currentRows(null);
    return phase != Phase.IDLE && phase != Phase.CLOSED && !current;
  }

  boolean matches(ResourceSearchIndex.Filter request) {
    refresh();
    return currentRows(Objects.requireNonNull(request));
  }

  private boolean currentRows(ResourceSearchIndex.Filter request) {
    if (phase != Phase.READY || source == null || acceptedSource != source) return false;
    Object work = token;
    var captured = source;
    var shown = accepted;
    try {
      var filter = liveFilter.get();
      return work == token && phase == Phase.READY && source == captured
          && acceptedSource == captured && accepted == shown && captured.current(state)
          && (request == null || request.equals(shown)) && shown.equals(filter);
    } catch (Throwable failure) {
      fail(work, failure);
      return false;
    }
  }

  boolean failed() {
    refresh();
    return phase == Phase.FAILED;
  }

  String message() {
    return message;
  }

  long version() {
    return version;
  }

  long builds() {
    return builds;
  }

  long queries() {
    return queries;
  }

  int indexed() {
    refresh();
    return index == null ? 0 : index.size();
  }

  void retry() {
    if (phase != Phase.FAILED) return;
    // Recapture even unsupported in-place source changes; never reuse a rejected source index.
    retire();
    source = null;
    index = null;
    desired = null;
    phase = Phase.WAITING;
    message = "Retrying the committed search.";
  }

  void cancelPending() {
    if (phase == Phase.READY || phase == Phase.IDLE || phase == Phase.FAILED
        || phase == Phase.CLOSED) return;
    retire();
    desired = null;
    phase = Phase.IDLE;
    message = "";
  }

  void clear() {
    if (phase == Phase.CLOSED) return;
    retire();
    source = acceptedSource = failedOwner = null;
    observed = failedFilter = null;
    index = null;
    desired = accepted = null;
    results = List.of();
    phase = Phase.IDLE;
    message = "";
  }

  private void retire() {
    var previous = task;
    token = new Object();
    version++;
    task = null;
    delivered = admitted = false;
    failedOwner = null;
    failedFilter = null;
    handoff.invalidate();
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    retire();
    phase = Phase.FAILED;
    failedOwner =
        new Source(state.project(), state.document.sessionToken(),
            state.project().project.uuid, state.revision, null);
    failedFilter = observed;
    message =
        StatusBarText.bounded(
            "Find search failed: " + BackgroundFailure.describe(failure)
                + ". Retry Search explicitly or change the filters.",
            3072);
    try {
      state.error("FIND_SEARCH_FAILED", EditorEvents.Subsystem.APPLICATION, message);
    } catch (Throwable ignored) {
      /* Keep failed search display-only; Retry and closing Find remain available. */
    }
  }

  @Override
  public void close() {
    clear();
    handoff.close();
    phase = Phase.CLOSED;
  }
}
