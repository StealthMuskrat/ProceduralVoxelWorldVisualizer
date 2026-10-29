package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;

/** A bounded multiline buffer, separate from both the document and the Properties draft. */
public final class ExpressionEditDialog {
  private final EditorState state;
  final DialogLayout layout;
  private final ExpressionEditSession session;
  private final Runnable staged;
  private final ImString text;
  private boolean pending = true;
  private String error = "";
  private final ExpressionSelectionCallback selection = new ExpressionSelectionCallback();
  private boolean selectionFocus;

  String bufferText() {
    return text.get();
  }

  ExpressionSelection.Range selectedRange() {
    return selection.applied();
  }

  private final Map<String, float[]> controls = new HashMap<>();

  public ExpressionEditDialog(EditorState state, ExpressionEditSession session, Runnable staged) {
    this.state = Objects.requireNonNull(state);
    layout = new DialogLayout(state, "Properties", 760, 520);
    this.session = Objects.requireNonNull(session);
    this.staged = Objects.requireNonNull(staged);
    // ImString capacity is in UTF-8 bytes; the language limit is in UTF-16 characters.
    text = new ImString(session.text(), 4 * ExpressionEditSession.MAX_BUFFER_CHARACTERS + 1);
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
    controls.clear();
    if (session.closed()) {
      selection.cancel();
      selectionFocus = false;
      return true;
    }
    if (pending) {
      layout.open("Expanded Expression");
      pending = false;
    }
    if (!layout.begin("Expanded Expression")) return false;
    ImGui.textWrapped(
        "Use in Properties stages only this text. Apply in Properties validates and commits the"
            + " complete resource. Cancel discards this buffer.");
    selection.context(state.project(), state.draft, session, session.text());
    boolean current = session.current(state);
    ExpressionFeedback.Result feedback = null;
    try {
      feedback = session.feedback(state);
    } catch (RuntimeException invalid) {
      error = message(invalid);
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
        error = message(invalid);
        feedback = null;
      }
    }
    controlRect("text");
    ImGui.text(
        text.get().length() + " / " + ExpressionEditSession.MAX_CHARACTERS + " UTF-16 characters");
    if (feedback != null) {
      if (feedback.valid()) ImGui.text("Valid " + feedback.type() + " expression");
      else if (feedback.input() != null)
        ImGui.textWrapped("! " + bounded("Input " + feedback.input() + ": " + feedback.error()));
      else
        ImGui.textWrapped(
            "! "
                + bounded(feedback.error())
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
    if (!current)
      ImGui.textWrapped(
          "The source or selection changed. This buffer is preserved; Cancel and reopen the"
              + " expression.");
    if (!error.isEmpty()) ImGui.textWrapped("! " + bounded(error));
    boolean done = false;
    ImGui.beginDisabled(!current);
    if (DialogLayout.button("Use in Properties")) {
      try {
        session.stage(state, text.get());
        done = true;
        // Refresh follows accepted, consumed staging; failure cannot turn it into a retry.
        try {
          staged.run();
        } catch (RuntimeException failure) {
          state.error(
              "EXPRESSION_EDIT_REFRESH_FAILED",
              EditorEvents.Subsystem.APPLICATION,
              "Expression staged in Properties; UI refresh failed: " + message(failure));
        }
      } catch (RuntimeException invalid) {
        error = message(invalid);
      }
    }
    controlRect("stage");
    ImGui.endDisabled();
    DialogLayout.sameLine("Cancel");
    if (DialogLayout.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
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

  private static String message(RuntimeException failure) {
    var value = failure.getMessage();
    return value == null || value.isBlank() ? failure.toString() : value;
  }

  private static String bounded(String value) {
    value = Objects.requireNonNullElse(value, "");
    return value.length() > 1024 ? value.substring(0, 1023) + "…" : value;
  }
}
