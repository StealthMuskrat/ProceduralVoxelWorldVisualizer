package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.ImGuiConfigFlags;

/** Resolve GLFW ownership from ImGui viewport identities, including detached Visualizers. */
final class ViewportPlatformInput implements FlightCursor.Backend {
  private final long mainWindow;

  ViewportPlatformInput(long mainWindow) {
    this.mainWindow = mainWindow;
  }

  FlightCursor.Window current() {
    return window(ImGui.getWindowViewport());
  }

  private FlightCursor.Window window(ImGuiViewport viewport) {
    int id = viewport.getID();
    long handle = viewport.getPlatformHandle();
    if (handle == 0 && id == ImGui.getMainViewport().getID()) handle = mainWindow;
    return handle > 0 ? new FlightCursor.Window(id, handle) : null;
  }

  @Override
  public boolean live(FlightCursor.Window window) {
    if (window == null) return false;
    var platform = ImGui.getPlatformIO();
    for (int i = 0; i < platform.getViewportsSize(); i++)
      if (window.equals(window(platform.getViewports(i)))) return true;
    return false;
  }

  @Override
  public boolean focused(FlightCursor.Window window) {
    return live(window) && glfwGetWindowAttrib(window.handle(), GLFW_FOCUSED) != 0;
  }

  @Override
  public int mode(FlightCursor.Window window) {
    return glfwGetInputMode(window.handle(), GLFW_CURSOR);
  }

  @Override
  public void mode(FlightCursor.Window window, int value) {
    glfwSetInputMode(window.handle(), GLFW_CURSOR, value);
  }

  @Override
  public boolean cursorUpdatesSuspended() {
    return ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
  }

  @Override
  public void cursorUpdatesSuspended(boolean value) {
    if (value) ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
    else ImGui.getIO().removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
  }
}
