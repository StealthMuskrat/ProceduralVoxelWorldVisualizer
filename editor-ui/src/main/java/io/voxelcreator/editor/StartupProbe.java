package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import imgui.ImGui;
import io.voxelcreator.model.ProjectIO;
import java.awt.image.BufferedImage;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;

/**
 * Deferred real Desktop startup/cleanup injection, with an empty project and no smoke resources.
 */
public final class StartupProbe {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("java.awt.headless", "true");
    System.setProperty("joml.nounsafe", "true");
    var cases = new ArrayList<Map<String, Object>>();
    for (String target :
        List.of(
            "window-created",
            "context-created",
            "backends-ready",
            "services-ready",
            "frame-rendered",
            "before-show",
            "shown",
            "visible")) {
      var directory = out.resolve(target);
      Files.createDirectories(directory);
      System.setProperty("voxel.configDir", directory.toString());
      var config = new UserConfig();
      config.workspace.width = 640;
      config.workspace.height = 480;
      var preferences = directory.resolve("user-config.json");
      config.save(preferences);
      byte[] previous = Files.readAllBytes(preferences);
      var app = new Desktop();
      var events = new ArrayList<Map<String, Object>>();
      var image = new LinkedHashMap<String, Object>();
      var injected = new IllegalStateException("startup fixture: " + target);
      app.observeStartup(
          stage -> {
            var state = app.startupState();
            var p = state.project();
            int resources =
                p.noise2D.size()
                    + p.noise3D.size()
                    + p.voxelTypes.size()
                    + p.voxelGroups.size()
                    + p.voxelSets.size()
                    + p.folders.size()
                    + p.worldGenerator.layers.size();
            int bookmarks =
                p.bookmarks.noise2D.size() + p.bookmarks.noise3D.size() + p.bookmarks.world.size();
            require(
                resources == 0
                    && bookmarks == 0
                    && !state.document.dirty()
                    && state.document.path() == null,
                "Normal startup added authoring content or a project file");
            require(state.engine.profiler().current().isEmpty(), "Startup enabled profiling");
            boolean visible = app.startupVisible();
            int vertices =
                stage.equals("frame-rendered") ? ImGui.getDrawData().getTotalVtxCount() : 0;
            events.add(
                Map.of(
                    "stage",
                    stage,
                    "visible",
                    visible,
                    "context",
                    app.startupContextOwned(),
                    "frames",
                    app.startupFrames(),
                    "vertices",
                    vertices,
                    "resources",
                    resources,
                    "bookmarks",
                    bookmarks,
                    "projectDirty",
                    false));
            if (Set.of(
                    "window-created",
                    "context-created",
                    "backends-ready",
                    "services-ready",
                    "frame-rendered",
                    "before-show")
                .contains(stage))
              require(!visible, "Editor became visible before its first complete frame");
            if (stage.equals("services-ready"))
              require(app.startupFrames() == 0, "Resize rendered before readiness");
            if (stage.equals("frame-rendered")) {
              require(vertices > 0 && app.startupFrames() > 0, "No completed editor draw data");
              if (target.equals("visible")) {
                try {
                  image.putAll(capture(app.getHandle(), directory.resolve("ready-frame.png")));
                } catch (java.io.IOException failure) {
                  throw new UncheckedIOException(failure);
                }
              }
            }
            if (stage.equals("shown") || stage.equals("presented"))
              require(visible, "Show did not map the ready editor window");
            if (stage.equals(target)) throw injected;
            if (stage.equals("presented")) app.stopStartupProbe();
          });
      Throwable failure = null;
      try {
        app.startDesktop();
      } catch (Throwable problem) {
        failure = problem;
      }
      if (target.equals("visible"))
        require(failure == null, "Normal ready-frame startup failed: " + failure);
      else
        require(
            failure == injected && failure.getSuppressed().length == 0,
            "Startup did not preserve its injected failure or cleanup failed");
      require(
          app.startupReleased() && !app.startupVisible() && app.getHandle() == 0,
          "Startup leaked owned context/window/workers");
      if (!target.equals("visible"))
        require(
            Arrays.equals(previous, Files.readAllBytes(preferences)),
            "Failed startup overwrote saved preferences");
      else {
        var saved = UserConfig.read(preferences);
        require(
            !saved.workspace.docking.isEmpty(), "Normal exit did not retain the ready workspace");
        require(
            events.stream().anyMatch(e -> e.get("stage").equals("presented")),
            "No completed presentation");
      }
      var receipt = new LinkedHashMap<String, Object>();
      receipt.put("scenario", target);
      receipt.put("stages", events);
      receipt.put("injected", !target.equals("visible"));
      receipt.put("released", true);
      receipt.put("failedPreferencesPreserved", !target.equals("visible"));
      receipt.put("initialPreferencesSha256", sha256(previous));
      receipt.put("savedPreferencesSha256", sha256(Files.readAllBytes(preferences)));
      receipt.put("projectEmpty", true);
      receipt.put("image", image);
      cases.add(receipt);
    }
    Files.writeString(
        out.resolve("startup-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.ofEntries(
                Map.entry("formatVersion", 1),
                Map.entry("cases", cases),
                Map.entry("realDesktop", true),
                Map.entry("emptyDefaultProject", true),
                Map.entry("physicalFirstPixelTimingVerified", false),
                Map.entry("physicalMaximizeVerified", false),
                Map.entry("physicalDpiVerified", false),
                Map.entry("physicalDetachedVerified", false),
                Map.entry("packagedWindows11Verified", false))));
    System.out.println(
        "STARTUP_NATIVE_PASS hidden initialization / ready editor backbuffer / empty project /"
            + " injected cleanup / preserved preferences");
  }

  private static Map<String, Object> capture(long handle, Path output) throws java.io.IOException {
    int[] width = {0}, height = {0};
    glfwGetFramebufferSize(handle, width, height);
    long count = (long) width[0] * height[0];
    require(
        width[0] > 0 && height[0] > 0 && count <= (64L << 20) / 4,
        "Startup readback exceeds its bound");
    long bytes = count * 4;
    var pixels = BufferUtils.createByteBuffer((int) bytes);
    int previous = glGetInteger(GL_READ_BUFFER);
    try {
      glReadBuffer(GL_BACK);
      glReadPixels(0, 0, width[0], height[0], GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    } finally {
      glReadBuffer(previous);
    }
    var image = new BufferedImage(width[0], height[0], BufferedImage.TYPE_INT_RGB);
    var colors = new HashSet<Integer>();
    for (int y = 0; y < height[0]; y++)
      for (int x = 0; x < width[0]; x++) {
        int at = ((height[0] - 1 - y) * width[0] + x) * 4;
        int color =
            Byte.toUnsignedInt(pixels.get(at)) << 16
                | Byte.toUnsignedInt(pixels.get(at + 1)) << 8
                | Byte.toUnsignedInt(pixels.get(at + 2));
        image.setRGB(x, y, color);
        if (colors.size() < 256) colors.add(color);
      }
    require(colors.size() >= 8, "First ready editor backbuffer is blank");
    require(ImageIO.write(image, "png", output.toFile()), "No PNG writer");
    return Map.of(
        "file",
        "visible/ready-frame.png",
        "width",
        width[0],
        "height",
        height[0],
        "colors",
        colors.size(),
        "sha256",
        sha256(Files.readAllBytes(output)));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
