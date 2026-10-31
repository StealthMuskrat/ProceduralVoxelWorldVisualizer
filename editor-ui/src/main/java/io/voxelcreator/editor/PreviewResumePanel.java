package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.*;
import java.util.function.*;

/** One explicit Resume decision, consumed before renderer refresh and scoped to a pause episode. */
final class PreviewResumePanel {
  private final EditorState state;
  private final SettingsActionScope scope = new SettingsActionScope();
  private final String nativeId = UUID.randomUUID().toString();
  private PreviewResumeContext shown;
  private int frame = -1, actionId;
  private DialogBounds.Rect actionBounds;

  PreviewResumePanel(EditorState state) {
    this.state = Objects.requireNonNull(state);
  }

  void draw(Supplier<PreviewResumeContext> source, LongSupplier clock, Runnable refresh) {
    var review = source.get();
    if (!review.paused()) {
      suspend();
      return;
    }
    int currentFrame = ImGui.getFrameCount();
    if (currentFrame < frame || currentFrame - frame > 1) suspend();
    frame = currentFrame;
    if (!review.same(shown)) shown = review;
    long request = scope.synchronize(review.view().document(), shown);
    ImGui.textWrapped(
        review.level() >= 3 ? "Paused. Resume at reduced quality." : "Paused. Resume generation.");
    ImGui.pushID("preview-resume:" + nativeId + ":" + request);
    actionId = ImGui.getID(review.kind().label);
    boolean clicked = DialogLayout.button(review.kind().label);
    actionBounds = PopupLayout.itemBounds();
    ImGui.popID();
    if (!clicked
        || ImGui.isKeyPressed(ImGuiKey.Escape, false)
        || !review.same(source.get())
        || !scope.current(review.view().document(), shown)) return;
    boolean admitted = false;
    try {
      long now = clock.getAsLong();
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)
          || !review.same(source.get())
          || !scope.current(review.view().document(), shown)) return;
      if (!review.pressure().resume(review.pauseVersion(), now)) return;
      admitted = true;
      // Refresh may admit another pause or another dialog. Never clean up that successor afterward.
      scope.release();
      shown = null;
      refresh.run();
    } catch (RuntimeException failure) {
      var detail = failure.getMessage();
      if (detail == null || detail.isBlank()) detail = failure.getClass().getName();
      state.error(
          admitted ? "PREVIEW_RESUME_REFRESH_FAILED" : "PREVIEW_RESUME_FAILED",
          EditorEvents.Subsystem.RENDERING,
          StatusBarText.clip(detail, 1024));
    }
  }

  void finishFrame(int currentFrame) {
    if (frame != currentFrame) suspend();
  }

  void suspend() {
    scope.release();
    shown = null;
    frame = -1;
    actionId = 0;
    actionBounds = null;
  }

  int actionId() {
    return actionId;
  }

  DialogBounds.Rect actionBounds() {
    return actionBounds;
  }

  float[] center() {
    return actionBounds == null
        ? null
        : new float[] {
          actionBounds.x() + actionBounds.width() / 2, actionBounds.y() + actionBounds.height() / 2
        };
  }
}
