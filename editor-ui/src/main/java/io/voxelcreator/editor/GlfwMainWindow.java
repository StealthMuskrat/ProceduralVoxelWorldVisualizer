package io.voxelcreator.editor;

import static io.voxelcreator.editor.WindowPlacement.*;
import static org.lwjgl.glfw.GLFW.*;

import java.util.*;

/** Native main-window operations in GLFW screen coordinates, never framebuffer pixels. */
final class GlfwMainWindow implements MainWindowPlacement.Host {
  private final long handle;
  private final int[] x = new int[1], y = new int[1], width = new int[1], height = new int[1];
  private final int[] left = new int[1], top = new int[1], right = new int[1], bottom = new int[1];

  GlfwMainWindow(long handle) {
    if (handle == 0) throw new IllegalArgumentException("Missing main GLFW window");
    this.handle = handle;
    glfwSetWindowSizeLimits(handle, MIN_WIDTH, MIN_HEIGHT, GLFW_DONT_CARE, GLFW_DONT_CARE);
  }

  public MainWindowPlacement.Sample sample() {
    boolean iconified = glfwGetWindowAttrib(handle, GLFW_ICONIFIED) != 0;
    boolean maximized = glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) != 0;
    glfwGetWindowPos(handle, x, y);
    glfwGetWindowSize(handle, width, height);
    if (iconified != (glfwGetWindowAttrib(handle, GLFW_ICONIFIED) != 0)
        || maximized != (glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) != 0)) return null;
    return new MainWindowPlacement.Sample(x[0], y[0], width[0], height[0], iconified, maximized);
  }

  public List<WorkArea> workAreas() {
    var result = new ArrayList<WorkArea>();
    long primary = glfwGetPrimaryMonitor();
    if (primary != 0) area(primary, result);
    var monitors = glfwGetMonitors();
    if (monitors != null)
      for (int index = monitors.position(); index < monitors.limit(); index++) {
        long monitor = monitors.get(index);
        if (monitor != 0 && monitor != primary) area(monitor, result);
      }
    return List.copyOf(result);
  }

  private void area(long monitor, List<WorkArea> result) {
    glfwGetMonitorWorkarea(monitor, x, y, width, height);
    if (width[0] > 0 && height[0] > 0) result.add(new WorkArea(x[0], y[0], width[0], height[0]));
  }

  public Insets frame() {
    glfwGetWindowFrameSize(handle, left, top, right, bottom);
    return new Insets(
        Math.max(0, left[0]), Math.max(0, top[0]), Math.max(0, right[0]), Math.max(0, bottom[0]));
  }

  public void restore() {
    glfwRestoreWindow(handle);
  }

  public void bounds(Rect rect) {
    glfwSetWindowSize(handle, rect.width(), rect.height());
    glfwSetWindowPos(handle, rect.x(), rect.y());
  }

  public void maximize() {
    glfwMaximizeWindow(handle);
  }
}
