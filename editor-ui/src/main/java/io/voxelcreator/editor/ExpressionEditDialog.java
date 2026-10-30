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
  private boolean focusEditor = true;
  private boolean revealFeedback;
  final SettingsPageChrome chrome = new SettingsPageChrome(2, 1);
  private DialogBounds.Rect editorBounds, feedbackBounds;
  private int editorWidgetId;
  private float lineHeight;

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
      focusEditor = false;
      return true;
    }
    if (pending) {
      layout.open("Expanded Expression");
      pending = false;
    }
    if (!layout.begin("Expanded Expression")) return false;
    chrome.begin("expanded-expression-body");
    if (probeScroll != null) {
      ImGui.setScrollY(probeScroll);
      probeScroll = null;
    }
    ImGui.textWrapped(
        "Use Text stages only this expression in Properties. Apply in Properties validates and"
            + " commits the complete resource. Cancel discards this expanded buffer.");
    selection.context(state.project(), state.draft, session, session.text());
    boolean current = session.current(state);
    ExpressionFeedback.Result feedback = null;
    try {
      feedback = session.feedback(state);
    } catch (RuntimeException invalid) {
      error = message(invalid);
      selection.cancel();
    }
    ImGui.textWrapped("Expression text");
    lineHeight = ImGui.getTextLineHeightWithSpacing();
    editorWidgetId = ImGui.getID("##expression-expanded");
    boolean focus = focusEditor || selection.pending();
    if (focus) ImGui.setKeyboardFocusHere();
    if (ImGui.inputTextMultiline(
        "##expression-expanded",
        text,
        Math.max(1, ImGui.getContentRegionAvailX()),
        ExpressionEditorLayout.editor(chrome.body().height(), ImGui.getTextLineHeightWithSpacing()),
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
      selection.context(state.project(), state.draft, session, session.text());
    }
    editorBounds = PopupLayout.itemBounds();
    controlRect("text");
    if (focus) {
      ImGui.setScrollHereY(.2f);
      if (ImGui.isItemActive() || ImGui.isItemFocused()) focusEditor = false;
    }
    ImGui.textWrapped(
        text.get().length() + " / " + ExpressionEditSession.MAX_CHARACTERS + " UTF-16 characters");
    UiScale.beginChild(
        "expanded-expression-feedback",
        0,
        ExpressionEditorLayout.feedback(
            chrome.body().height(), ImGui.getTextLineHeightWithSpacing()),
        true);
    feedbackBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    if (revealFeedback) ImGui.setScrollY(0);
    if (feedback != null) {
      if (feedback.valid()) ImGui.textWrapped("Valid " + feedback.type() + " expression");
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
    }
    if (!current)
      ImGui.textWrapped(
          "The source or selection changed. This buffer is preserved; Cancel and reopen the"
              + " expression.");
    if (!error.isEmpty()) ImGui.textWrapped("! " + bounded(error));
    ImGui.endChild();
    if (revealFeedback) {
      ImGui.setScrollHereY(.5f);
      revealFeedback = false;
    }
    chrome.end();
    ImGui.beginDisabled(!current);
    boolean stage = chrome.action("stage", "Use Text");
    controlRect("stage");
    ImGui.endDisabled();
    boolean cancel =
        chrome.action("cancel", "Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false);
    controlRect("cancel");
    boolean problem = feedback != null && !feedback.valid() || !error.isEmpty() || !current;
    ImGui.beginDisabled(!problem);
    boolean showError = chrome.action("selectError", "Show Error");
    controlRect("selectError");
    ImGui.endDisabled();
    boolean done = false;
    if (cancel) {
      session.cancel();
      done = true;
    } else if (stage && current) {
      try {
        session.stage(state, text.get());
        done = true;
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
    } else if (showError && problem) {
      focusEditor = false;
      if (feedback != null
          && !feedback.valid()
          && feedback.input() == null
          && session.text().equals(text.get())) {
        selection.request(text.get(), feedback);
      } else {
        selection.cancel();
        revealFeedback = true;
      }
    }
    if (done) {
      focusEditor = false;
      selection.cancel();
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
    return done;
  }

  DialogBounds.Rect editorBounds() {
    return editorBounds;
  }

  DialogBounds.Rect feedbackBounds() {
    return feedbackBounds;
  }

  int editorWidgetId() {
    return editorWidgetId;
  }

  float lineHeight() {
    return lineHeight;
  }

  void probeScroll(float value) {
    probeScroll = value;
  }

  private Float probeScroll;

  private static String message(RuntimeException failure) {
    var value = failure.getMessage();
    return value == null || value.isBlank() ? failure.toString() : value;
  }

  private static String bounded(String value) {
    value = Objects.requireNonNullElse(value, "");
    return value.length() > 1024 ? value.substring(0, 1023) + "…" : value;
  }
}
