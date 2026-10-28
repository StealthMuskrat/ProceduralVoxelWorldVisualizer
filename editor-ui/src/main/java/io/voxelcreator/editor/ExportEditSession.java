package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Editor export owns a frozen source and requires its exact displayed preview before file I/O. */
final class ExportEditSession {
  private final EditorState state;
  private final UUID projectId;
  private ProjectData source, frozen;
  private long revision, preparations;
  private JsonNode sourceValue;
  private ResourceTreeIndex catalog;
  private ResourceExportSession model;
  private ResourceExportSession.Preview preview, shown;
  private Set<UUID> selected = Set.of();
  private boolean closed, forcedStale;
  private int unavailable;

  ExportEditSession(EditorState state, Set<UUID> selected) {
    this.state = Objects.requireNonNull(state);
    projectId = state.project().project.uuid;
    reload(selected);
  }

  boolean current() {
    return !closed
        && !forcedStale
        && source == state.project()
        && revision == state.revision
        && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean canReload() {
    return !closed && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean closed() {
    return closed;
  }

  Set<UUID> selected() {
    return selected;
  }

  ResourceTreeIndex catalog() {
    return catalog;
  }

  ResourceExportSession.Preview preview() {
    return preview;
  }

  long preparations() {
    return preparations;
  }

  int unavailableCount() {
    return unavailable;
  }

  void select(Set<UUID> selection) {
    if (!current()) throw new IllegalStateException("Reload the changed export source first");
    if (selected.equals(selection)) return;
    var ids = Set.copyOf(selection);
    selected = ids;
    shown = null;
    preview = null;
    prepare();
  }

  private void prepare() {
    unavailable = (int) selected.stream().filter(id -> catalog.get(id) == null).count();
    if (unavailableCount() != 0) return;
    model.prepare(frozen, 0, selected);
    preview = model.preview();
    preparations++;
  }

  void reload() {
    reload(selected);
  }

  private void reload(Set<UUID> selection) {
    if (!canReload()) throw new IllegalStateException("The project was replaced; reopen Export");
    var value = ProjectIO.JSON.valueToTree(state.project());
    var snapshot = ProjectIO.JSON.convertValue(value, ProjectData.class);
    var index = new ResourceTreeIndex(snapshot);
    var ids = Set.copyOf(selection);
    source = state.project();
    revision = state.revision;
    sourceValue = value;
    frozen = snapshot;
    catalog = index;
    model = new ResourceExportSession();
    selected = ids;
    shown = preview = null;
    forcedStale = false;
    prepare();
  }

  ResourceExportSession preparedModel() {
    if (!current() || preview == null)
      throw new IllegalStateException("Review a current export selection first");
    model.prepare(frozen, 0, selected);
    if (model.preview() != preview) {
      preview = model.preview();
      shown = null;
      preparations++;
    }
    return model;
  }

  void markShown(ResourceExportSession.Preview value) {
    if (current() && value != null && value == preview) shown = value;
  }

  boolean canExport() {
    return current() && !selected.isEmpty() && preview != null && shown == preview;
  }

  void write(Path destination, ResourcePackageFiles files) throws IOException {
    if (!current() || !sourceValue.equals(ProjectIO.JSON.valueToTree(state.project()))) {
      if (!closed) forcedStale = true;
      throw new IllegalStateException("Export source changed; Reload Preview before exporting");
    }
    if (selected.isEmpty()) throw new IllegalStateException("Select resources to export");
    if (preview == null || shown != preview)
      throw new IllegalStateException("Display the current export preview before exporting");
    files.write(destination, preview.resources(), state.document.path());
    closed = true;
    shown = null;
  }

  void cancel() {
    closed = true;
    shown = null;
  }
}
