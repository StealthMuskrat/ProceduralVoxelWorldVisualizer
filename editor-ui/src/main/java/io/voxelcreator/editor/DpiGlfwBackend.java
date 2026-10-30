package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;

import imgui.ImGui;
import imgui.glfw.ImGuiImplGlfw;

/** Refresh backend work/DPI inventory before native NewFrame, including live OS scale changes. */
final class DpiGlfwBackend extends ImGuiImplGlfw {
  private final long mainWindow;
  private final float[] contentX = new float[1], contentY = new float[1];
  private float mainContent = 1;

  DpiGlfwBackend(long mainWindow) {
    if (mainWindow == 0) throw new IllegalArgumentException("Missing main GLFW window");
    this.mainWindow = mainWindow;
  }

  float mainContentScale() {
    return mainContent;
  }

  @Override
  public void newFrame() {
    super.newFrame();
    glfwGetWindowContentScale(mainWindow, contentX, contentY);
    mainContent = UiDpiPolicy.factor(contentX[0], mainContent);
    updateMonitors();
    var platform = ImGui.getPlatformIO();
    for (int i = 0; i < platform.getMonitorsSize(); i++) {
      var monitor = platform.getMonitors(i);
      monitor.setDpiScale(UiDpiPolicy.factor(monitor.getDpiScale(), mainContent));
    }
  }
}
