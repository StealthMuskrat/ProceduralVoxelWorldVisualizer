package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Expanded text is private even from Properties until explicitly staged. */
public final class ExpressionEditSession {
  public static final int MAX_CHARACTERS = 16384;
  public static final int MAX_BUFFER_CHARACTERS = 4 * MAX_CHARACTERS;
  private final EditorState editor;
  private final ProjectData project;
  private final long revision;
  private final UUID projectId, ownerId, itemId;
  private final String kind;
  private final List<UUID> selection;
  private final Noise owner;
  private final Item item;
  private final JsonNode sourceNode, source, projectBase;
  private final boolean sourcePresent;
  private final ExpressionFeedback feedback = new ExpressionFeedback();
  private String text;
  private boolean closed, forcedStale;
  private Item candidate;
  private JsonNode candidateBase;
  private String candidateText;
  private long preparations;

  public ExpressionEditSession(EditorState state, Noise owner, Item item) {
    if (state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !(EditorState.find(state.project(), owner.uuid) instanceof Noise)
        || !owner.processing.contains(item)
        || !"EXPRESSION".equals(item.kind))
      throw new IllegalArgumentException("Select the expression's current Properties draft");
    editor = state;
    this.project = state.project();
    this.revision = state.revision;
    projectId = project.project.uuid;
    ownerId = owner.uuid;
    itemId = item.uuid;
    kind = state.selectedKind;
    selection = List.copyOf(state.multiSelection);
    this.owner = owner;
    this.item = item;
    sourcePresent = item.parameters.containsKey("expression");
    sourceNode = item.parameters.get("expression");
    source = sourceNode == null ? null : sourceNode.deepCopy();
    text = sourceNode == null ? "" : sourceNode.asText();
    if (text.length() > MAX_CHARACTERS)
      throw new IllegalArgumentException(
          "Expression exceeds expanded editor limit " + MAX_CHARACTERS);
    projectBase = ProjectIO.JSON.valueToTree(project);
  }

  public String text() {
    return text;
  }

  public void edit(String value) {
    if (closed) throw new IllegalStateException("Expression editor is closed");
    Objects.requireNonNull(value);
    if (value.length() > MAX_BUFFER_CHARACTERS)
      throw new IllegalArgumentException(
          "Expanded expression buffer limit " + MAX_BUFFER_CHARACTERS);
    text = value;
  }

  public void cancel() {
    closed = true;
    candidate = null;
    candidateBase = null;
    candidateText = null;
  }

  public boolean closed() {
    return closed;
  }

  /** Frame-time ownership without serializing the complete project. */
  public boolean current(EditorState state) {
    return !closed
        && !forcedStale
        && state == editor
        && state.project() == project
        && state.revision == revision
        && Objects.equals(projectId, project.project.uuid)
        && state.draft == owner
        && Objects.equals(ownerId, owner.uuid)
        && Objects.equals(state.selected, ownerId)
        && Objects.equals(kind, state.selectedKind)
        && selection.equals(List.copyOf(state.multiSelection))
        && owner.processing.contains(item)
        && Objects.equals(itemId, item.uuid)
        && "EXPRESSION".equals(item.kind)
        && sourcePresent == item.parameters.containsKey("expression")
        && sourceNode == item.parameters.get("expression");
  }

  private void requireCurrent(EditorState state) {
    if (closed) throw new IllegalStateException("Expression editor is closed");
    if (!current(state) || !Objects.equals(source, item.parameters.get("expression")))
      throw new IllegalStateException("Expression source changed; cancel and reopen the editor");
  }

  public ExpressionFeedback.Result feedback(EditorState state) {
    requireCurrent(state);
    if (text.length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Expression length limit " + MAX_CHARACTERS);
    var current = ProjectIO.JSON.valueToTree(item);
    if (candidate == null || !current.equals(candidateBase)) {
      candidate = ProjectIO.JSON.convertValue(current, Item.class);
      candidateBase = current;
      candidateText = null;
      preparations++;
    }
    if (!Objects.equals(candidateText, text)) {
      candidate.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
      candidateText = text;
    }
    return feedback.inspect(project, owner, candidate);
  }

  long preparations() {
    return preparations;
  }

  /**
   * Stage only text. Syntax validity remains the responsibility of the ordinary Apply transaction.
   */
  public void stage(EditorState state) {
    requireCurrent(state);
    if (text.length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Expression length limit " + MAX_CHARACTERS);
    if (!projectBase.equals(ProjectIO.JSON.valueToTree(state.project()))) {
      forcedStale = true;
      throw new IllegalStateException("Expression project changed; cancel and reopen the editor");
    }
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    cancel();
  }

  /** Reject stale displayed input instead of staging a previous accepted buffer. */
  void stage(EditorState state, String displayed) {
    if (!Objects.equals(text, displayed))
      throw new IllegalStateException(
          "Displayed expression differs from the staged buffer; correct it before using it");
    stage(state);
  }
}
