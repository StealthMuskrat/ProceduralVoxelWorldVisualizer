package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** One committed index, one displayed result and at most one trailing typed query. */
public final class ResourceSearchSession {
  public static final int DEBOUNCE_ROWS = 1024;
  public static final long DEBOUNCE_NANOS = 150_000_000L;
  private final int debounceRows;
  private final long debounceNanos;
  private ProjectData project;
  private long revision, builds, queries, presentationVersion, pendingSince;
  private ResourceSearchIndex index;
  private ResourceSearchIndex.Filter filter, pending;
  private List<ResourceSearchIndex.Hit> results = List.of();

  public ResourceSearchSession() {
    this(DEBOUNCE_ROWS, DEBOUNCE_NANOS);
  }

  ResourceSearchSession(int debounceRows, long debounceNanos) {
    if (debounceRows < 1 || debounceNanos < 1)
      throw new IllegalArgumentException("Positive debounce row count and duration required");
    this.debounceRows = debounceRows;
    this.debounceNanos = debounceNanos;
  }

  /** Explicit requests, scope changes and source replacement never wait for typing. */
  public List<ResourceSearchIndex.Hit> search(
      ProjectData current, long currentRevision, ResourceSearchIndex.Filter request) {
    synchronize(current, currentRevision);
    return apply(Objects.requireNonNull(request));
  }

  /** Caller supplies a monotonic nano clock; no timers, jobs or retained query queue. */
  public List<ResourceSearchIndex.Hit> searchDebounced(
      ProjectData current, long currentRevision, ResourceSearchIndex.Filter request, long now) {
    Objects.requireNonNull(request);
    synchronize(current, currentRevision);
    if (request.equals(filter)
        || filter == null
        || index.size() < debounceRows
        || request.query().isEmpty()
        || !sameScope(filter, request)) return apply(request);
    if (!request.equals(pending)) {
      pending = request;
      pendingSince = now;
      presentationVersion++;
    } else {
      long elapsed = now - pendingSince;
      if (elapsed >= debounceNanos) return apply(request);
      if (elapsed < 0) pendingSince = now;
    }
    return results;
  }

  private void synchronize(ProjectData current, long currentRevision) {
    Objects.requireNonNull(current);
    if (project == current && revision == currentRevision && index != null) return;
    // Build before publishing so a rejected index never becomes a partial current index.
    var replacement = new ResourceSearchIndex(current);
    index = replacement;
    project = current;
    revision = currentRevision;
    filter = pending = null;
    results = List.of();
    builds++;
    presentationVersion++;
  }

  private List<ResourceSearchIndex.Hit> apply(ResourceSearchIndex.Filter request) {
    if (!request.equals(filter)) {
      var replacement = index.search(request);
      results = replacement;
      filter = request;
      queries++;
      presentationVersion++;
    }
    cancelPending();
    return results;
  }

  private static boolean sameScope(ResourceSearchIndex.Filter a, ResourceSearchIndex.Filter b) {
    return a.kind().equals(b.kind())
        && a.state().equals(b.state())
        && a.root().equals(b.root())
        && Objects.equals(a.directory(), b.directory())
        && Objects.equals(a.reference(), b.reference())
        && a.containers() == b.containers()
        && a.mode() == b.mode();
  }

  public boolean pending() {
    return pending != null;
  }

  public boolean matches(ResourceSearchIndex.Filter request) {
    return pending == null && request.equals(filter);
  }

  /** Retire native row gestures across requests, completion, cancellation and source changes. */
  public long presentationVersion() {
    return presentationVersion;
  }

  public void cancelPending() {
    if (pending != null) presentationVersion++;
    pending = null;
  }

  public long builds() {
    return builds;
  }

  public long queries() {
    return queries;
  }

  public int indexed() {
    return index == null ? 0 : index.size();
  }

  public void clear() {
    project = null;
    index = null;
    filter = pending = null;
    results = List.of();
    presentationVersion++;
  }
}
