package io.voxelcreator.editor;

import imgui.ImGui;

/** Frame-scoped UI-thread DPI composition. No context/window/monitor wrapper survives a call. */
final class UiDpi {
  private static final ThreadLocal<UiDpiPolicy.Frame> FRAME = new ThreadLocal<>();

  private UiDpi() {}

  static void begin(UiDpiPolicy.Frame frame) {
    FRAME.set(java.util.Objects.requireNonNull(frame));
  }

  static void clear() {
    FRAME.remove();
  }

  static float windowRatio() {
    var frame = FRAME.get();
    if (frame == null) return 1;
    return contentScale() / frame.mainContentScale();
  }

  static float contentScale() {
    var frame = FRAME.get();
    if (frame == null) return 1;
    var viewport = ImGui.getWindowViewport();
    if (viewport == null
        || !viewport.isValidPtr()
        || viewport.getID() == ImGui.getMainViewport().getID()) return frame.mainContentScale();
    return UiDpiPolicy.factor(viewport.getDpiScale(), frame.mainContentScale());
  }
}
