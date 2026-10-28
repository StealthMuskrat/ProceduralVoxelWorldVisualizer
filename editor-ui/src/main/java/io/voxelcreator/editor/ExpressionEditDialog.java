package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;

/** A bounded multiline buffer, separate from both the document and the Properties draft. */
public final class ExpressionEditDialog {
  private final EditorState state;
  private final ExpressionEditSession session;
  private final Runnable staged;
  private final ImString text;
  private boolean pending = true;
  private String error = "";
  private final ExpressionSelectionCallback selection = new ExpressionSelectionCallback();
  private boolean selectionFocus;

  String bufferText() {
    return session.text();
  }

  ExpressionSelection.Range selectedRange() {
    return selection.applied();
  }

  private final Map<String, float[]> controls = new HashMap<>();

  public ExpressionEditDialog(EditorState state, ExpressionEditSession session, Runnable staged) {
    this.state = state;
    this.session = session;
    this.staged = staged;
    // ImString capacity is in UTF-8 bytes; the language limit is in UTF-16 characters.
    text = new ImString(session.text(), 4 * ExpressionEditSession.MAX_CHARACTERS + 1);
  }

  float[] control(String name) {
    return controls.get(name);
  }

  private void controlRect(String name) {
    controls.put(
        name,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  public boolean draw() {
    if (pending) {
      ImGui.openPopup("Expanded Expression");
      pending = false;
    }
    ImGui.setNextWindowSize(760, 520, ImGuiCond.FirstUseEver);
    if (!ImGui.beginPopupModal("Expanded Expression")) return false;
    ImGui.textWrapped(
        "Use in Properties stages only this text. Apply in Properties validates and commits the"
            + " complete resource. Cancel discards this buffer.");
    selection.context(state.project(), state.draft, session, session.text());
    ExpressionFeedback.Result feedback = null;
    try {
      feedback = session.feedback(state);
    } catch (RuntimeException invalid) {
      error = invalid.getMessage();
      selection.cancel();
      selectionFocus = false;
    }
    if (ImGui.isWindowAppearing() || selectionFocus) ImGui.setKeyboardFocusHere();
    selectionFocus = false;
    if (ImGui.inputTextMultiline(
        "##expression-expanded",
        text,
        ImGui.getContentRegionAvailX(),
        Math.max(140, ImGui.getContentRegionAvailY() - 175),
        ImGuiInputTextFlags.CallbackAlways,
        selection)) {
      error = "";
      try {
        session.edit(text.get());
        feedback = session.feedback(state);
      } catch (RuntimeException invalid) {
        error = invalid.getMessage();
        feedback = null;
      }
    }
    controlRect("text");
    ImGui.text(
        text.get().length() + " / " + ExpressionEditSession.MAX_CHARACTERS + " UTF-16 characters");
    if (feedback != null) {
      if (feedback.valid()) ImGui.text("Valid " + feedback.type() + " expression");
      else if (feedback.input() != null)
        ImGui.textWrapped("! Input " + feedback.input() + ": " + feedback.error());
      else
        ImGui.textWrapped(
            "! "
                + feedback.error()
                + " ["
                + feedback.start()
                + ".."
                + feedback.end()
                + ", zero-based UTF-16, end exclusive]");
      if (!feedback.valid() && feedback.input() == null) {
        if (ImGui.smallButton("Select Expression Error")) {
          selection.request(text.get(), feedback);
          selectionFocus = true;
        }
        controlRect("selectError");
      }
    }
    if (!error.isEmpty()) ImGui.textWrapped("! " + error);
    boolean done = false;
    if (ImGui.button("Use in Properties")) {
      try {
        session.stage(state, text.get());
        staged.run();
        done = true;
      } catch (RuntimeException invalid) {
        error = invalid.getMessage();
      }
    }
    controlRect("stage");
    ImGui.sameLine();
    if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.cancel();
      done = true;
    }
    controlRect("cancel");
    if (done) {
      selection.cancel();
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
    return done;
  }
}
