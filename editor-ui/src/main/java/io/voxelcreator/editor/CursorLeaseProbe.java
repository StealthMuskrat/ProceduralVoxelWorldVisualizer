package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.ImGui;
import imgui.flag.ImGuiConfigFlags;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred real GLFW cursor lease reference; detached ImGui layout acceptance is separate. */
final class CursorLeaseProbe {
  static void run(Path out, long mainWindow) throws Exception {
    Files.createDirectories(out);
    boolean mainVisible = glfwGetWindowAttrib(mainWindow, GLFW_VISIBLE) != 0;
    long secondary = glfwCreateWindow(64, 64, "Cursor lease reference", 0, 0);
    require(secondary != 0, "Secondary native window creation");
    var main = new FlightCursor.Window(1, mainWindow);
    var detached = new FlightCursor.Window(2, secondary);
    var live = new HashSet<>(Set.of(main, detached));
    boolean previousPolicy = ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
    int mainMode = glfwGetInputMode(mainWindow, GLFW_CURSOR);
    FlightCursor.Backend backend =
        new FlightCursor.Backend() {
          public boolean live(FlightCursor.Window window) {
            return live.contains(window);
          }

          public boolean focused(FlightCursor.Window window) {
            require(live(window), "Native focus read from a destroyed window");
            return glfwGetWindowAttrib(window.handle(), GLFW_FOCUSED) != 0;
          }

          public int mode(FlightCursor.Window window) {
            require(live(window), "Native mode read from a destroyed window");
            return glfwGetInputMode(window.handle(), GLFW_CURSOR);
          }

          public void mode(FlightCursor.Window window, int value) {
            require(live(window), "Native mode write to a destroyed window");
            glfwSetInputMode(window.handle(), GLFW_CURSOR, value);
          }

          public boolean cursorUpdatesSuspended() {
            return ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
          }

          public void cursorUpdatesSuspended(boolean value) {
            if (value) ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
            else ImGui.getIO().removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
          }
        };
    try (var cursor = new FlightCursor(backend, GLFW_CURSOR_DISABLED)) {
      glfwShowWindow(mainWindow);
      glfwShowWindow(secondary);
      glfwFocusWindow(secondary);
      glfwPollEvents();
      require(backend.focused(detached) && !backend.focused(main), "Secondary native focus");
      glfwSetInputMode(secondary, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
      require(cursor.sync(detached), "Secondary capture");
      require(
          glfwGetInputMode(secondary, GLFW_CURSOR) == GLFW_CURSOR_DISABLED,
          "Secondary cursor was not disabled");
      require(
          glfwGetInputMode(mainWindow, GLFW_CURSOR) == mainMode,
          "Secondary capture changed the main cursor");
      require(backend.cursorUpdatesSuspended(), "Backend cursor policy was not suspended");
      glfwFocusWindow(mainWindow);
      glfwPollEvents();
      require(!cursor.sync(detached), "Unfocused capture was retained");
      require(
          glfwGetInputMode(secondary, GLFW_CURSOR) == GLFW_CURSOR_HIDDEN,
          "Previous hidden mode was not restored");
      require(
          backend.cursorUpdatesSuspended() == previousPolicy, "Backend policy was not restored");
      require(cursor.sync(main), "Main capture after native focus transfer");
      require(glfwGetInputMode(mainWindow, GLFW_CURSOR) == GLFW_CURSOR_DISABLED, "Main capture");
      require(!cursor.sync(null), "Main release");
      require(glfwGetInputMode(mainWindow, GLFW_CURSOR) == mainMode, "Main mode restoration");
      glfwFocusWindow(secondary);
      glfwPollEvents();
      require(cursor.sync(detached), "Secondary recapture");
      live.remove(detached);
      glfwDestroyWindow(secondary);
      secondary = 0;
      require(!cursor.sync(detached), "Destroyed window capture was retained");
      require(
          backend.cursorUpdatesSuspended() == previousPolicy, "Destroyed-window policy cleanup");
      Files.writeString(
          out.resolve("cursor-lease-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "secondaryCapture",
                  true,
                  "mainUnchanged",
                  true,
                  "focusLossRelease",
                  true,
                  "hiddenModeRestored",
                  true,
                  "mainCaptureTransfer",
                  true,
                  "destroyedWindowSkipped",
                  true,
                  "backendPolicyRestored",
                  true)));
      System.out.println(
          "CURSOR_LEASE_NATIVE_PASS secondary capture / focus transfer / mode restoration /"
              + " dead-window cleanup");
    } finally {
      if (secondary != 0) glfwDestroyWindow(secondary);
      if (previousPolicy) ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
      else ImGui.getIO().removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
      if (mainVisible) glfwFocusWindow(mainWindow);
      else glfwHideWindow(mainWindow);
      glfwPollEvents();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
