package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseAuthoring;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;

/** One private structural edit tied to its opening Properties and complete project values. */
final class NoiseEditSession {
  private final EditorState state;
  private final ProjectData project;
  private final Noise owner;
  private final long revision, seedVersion;
  private final String kind;
  private final List<UUID> selection;
  private final JsonNode draftBase, projectBase;
  private final Action action;
  private final UUID target;
  private Resolution resolution;
  private UUID replacement;
  private NoiseAuthoring.Plan plan;
  private boolean closed;

  NoiseEditSession(EditorState state, Noise owner, Request request) {
    Objects.requireNonNull(request);
    if (state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !(EditorState.find(state.project(), owner.uuid) instanceof Noise))
      throw new IllegalArgumentException("Select the noise's current Properties draft");
    this.state = state;
    this.owner = owner;
    project = state.project();
    revision = state.revision;
    seedVersion = state.seedInputs.version(state);
    kind = state.selectedKind;
    selection = List.copyOf(state.multiSelection);
    draftBase = ProjectIO.JSON.valueToTree(owner);
    projectBase = ProjectIO.JSON.valueToTree(project);
    action = request.action();
    target = request.target();
    resolution = request.resolution();
    replacement = request.replacement();
    plan = NoiseAuthoring.plan(project, owner, request);
  }

  boolean current() {
    return !closed
        && state.project() == project
        && state.revision == revision
        && state.draft == owner
        && Objects.equals(state.selected, owner.uuid)
        && Objects.equals(state.selectedKind, kind)
        && selection.equals(List.copyOf(state.multiSelection))
        && seedVersion == state.seedInputs.version(state);
  }

  private void requireCurrent() {
    if (!current()
        || !draftBase.equals(ProjectIO.JSON.valueToTree(owner))
        || !projectBase.equals(ProjectIO.JSON.valueToTree(state.project())))
      throw new IllegalStateException("Draft or project changed; Cancel and reopen this edit");
  }

  NoiseAuthoring.Plan preview() {
    return plan;
  }

  Resolution resolution() {
    return resolution;
  }

  UUID replacement() {
    return replacement;
  }

  void choose(Resolution resolution, UUID replacement) {
    requireCurrent();
    Objects.requireNonNull(resolution);
    if (this.resolution == resolution && Objects.equals(this.replacement, replacement)) return;
    var next =
        NoiseAuthoring.plan(project, owner, new Request(action, target, resolution, replacement));
    this.resolution = resolution;
    this.replacement = replacement;
    plan = next;
  }

  /** Publication is private to Properties; the caller handles focus only after this is consumed. */
  UUID stage() {
    requireCurrent();
    plan.applyTo(owner);
    closed = true;
    return plan.selected();
  }

  void cancel() {
    closed = true;
  }

  boolean closed() {
    return closed;
  }
}
