package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Expanded text is private even from Properties until explicitly staged. */
public final class ExpressionEditSession {
  public static final int MAX_CHARACTERS = 16384;
  private final ProjectData project;
  private final long revision;
  private final Noise owner;
  private final Item item;
  private final JsonNode source;
  private final boolean sourcePresent;
  private final ExpressionFeedback feedback = new ExpressionFeedback();
  private String text;
  private boolean closed;

  public ExpressionEditSession(EditorState state, Noise owner, Item item) {
    if (state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !owner.processing.contains(item)
        || !"EXPRESSION".equals(item.kind))
      throw new IllegalArgumentException("Select the expression's current Properties draft");
    this.project = state.project();
    this.revision = state.revision;
    this.owner = owner;
    this.item = item;
    sourcePresent = item.parameters.containsKey("expression");
    var original = item.parameters.get("expression");
    source = original == null ? null : original.deepCopy();
    text = original == null ? "" : original.asText();
    if (text.length() > MAX_CHARACTERS)
      throw new IllegalArgumentException(
          "Expression exceeds expanded editor limit " + MAX_CHARACTERS);
  }

  public String text() {
    return text;
  }

  public void edit(String value) {
    if (closed) throw new IllegalStateException("Expression editor is closed");
    text = Objects.requireNonNull(value);
  }

  public void cancel() {
    closed = true;
  }

  private void current(EditorState state) {
    if (closed) throw new IllegalStateException("Expression editor is closed");
    if (state.project() != project
        || state.revision != revision
        || state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !owner.processing.contains(item)
        || !"EXPRESSION".equals(item.kind)
        || sourcePresent != item.parameters.containsKey("expression")
        || !Objects.equals(source, item.parameters.get("expression")))
      throw new IllegalStateException("Expression source changed; cancel and reopen the editor");
  }

  public ExpressionFeedback.Result feedback(EditorState state) {
    current(state);
    var candidate = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(item), Item.class);
    candidate.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    return feedback.inspect(project, owner, candidate);
  }

  /**
   * Stage only text. Syntax validity remains the responsibility of the ordinary Apply transaction.
   */
  public void stage(EditorState state) {
    current(state);
    if (text.length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Expression length limit " + MAX_CHARACTERS);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    closed = true;
  }
}
