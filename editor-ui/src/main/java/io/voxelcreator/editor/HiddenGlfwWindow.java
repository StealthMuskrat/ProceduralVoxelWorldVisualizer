package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.app.Configuration;
import java.util.Locale;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

/** Standalone desktop GLFW ownership. Allocation never shows, swaps or polls a cleared window. */
final class HiddenGlfwWindow implements AutoCloseable {
  private long handle;
  private boolean initialized, callbackInstalled, capabilities, closed;
  private GLFWErrorCallback error, previous;
  private String glsl;

  void open(Configuration config, Runnable resized) {
    if (closed || initialized || callbackInstalled)
      throw new IllegalStateException("Native window already opened");
    error = GLFWErrorCallback.createPrint(System.err);
    previous = glfwSetErrorCallback(error);
    callbackInstalled = true;
    if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");
    initialized = true;
    glfwDefaultWindowHints();
    boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, mac ? 2 : 0);
    if (mac) {
      glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
      glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    }
    glsl = mac ? "#version 150" : "#version 130";
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    handle = glfwCreateWindow(config.getWidth(), config.getHeight(), config.getTitle(), 0, 0);
    if (handle == 0) throw new IllegalStateException("Failed to create the GLFW window");
    glfwMakeContextCurrent(handle);
    capabilities = true;
    GL.createCapabilities();
    glfwSwapInterval(1);
    glfwSetWindowSizeCallback(handle, (window, width, height) -> resized.run());
  }

  long handle() {
    return handle;
  }

  String glsl() {
    return glsl;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    var cleanup = new EditorLifecycle();
    // Register in acquisition order; cleanup proceeds through every failed native operation.
    if (error != null)
      cleanup.own(
          () -> {
            if (callbackInstalled) glfwSetErrorCallback(previous);
            callbackInstalled = false;
            error.free();
          });
    if (initialized)
      cleanup.own(
          () -> {
            glfwTerminate();
            initialized = false;
          });
    if (capabilities)
      cleanup.own(
          () -> {
            GL.setCapabilities(null);
            capabilities = false;
          });
    if (handle != 0) {
      long window = handle;
      handle = 0;
      cleanup.own(
          () -> {
            glfwDestroyWindow(window);
          });
      cleanup.own(() -> Callbacks.glfwFreeCallbacks(window));
    }
    Throwable failure = cleanup.close(null);
    if (failure instanceof Error error) throw error;
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure != null) throw new IllegalStateException("Native cleanup failed", failure);
  }
}
