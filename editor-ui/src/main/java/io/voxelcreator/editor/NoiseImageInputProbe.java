package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;

/** Native ImGui event probe of the same handler used by the production viewport. */
public final class NoiseImageInputProbe {
  private static NoiseImageNavigation pose = new NoiseImageNavigation(-17.25, 31.5, .75);
  private static float px, py;
  private static final float WIDTH = 640, HEIGHT = 360;
  private static int changes;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1000, 800);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        frames(4);
        io.addMousePosEvent(px + WIDTH * .25f, py + HEIGHT * .35f);
        frame();
        double u = (io.getMousePosX() - px) / (double) WIDTH,
            v = (io.getMousePosY() - py) / (double) HEIGHT;
        var before = pose;
        io.addMouseWheelEvent(0, 2);
        frame();
        var zoomed = pose;
        require(zoomed.step() < before.step(), "Wheel failed to zoom");
        near(
            before.x() + (u * 256 - 128) * before.step(),
            zoomed.x() + (u * 256 - 128) * zoomed.step());
        near(
            before.z() + (v * 256 - 128) * before.step(),
            zoomed.z() + (v * 256 - 128) * zoomed.step());
        io.addMouseWheelEvent(0, -2);
        frame();
        near(before.x(), pose.x());
        near(before.z(), pose.z());
        near(before.step(), pose.step());
        io.addMousePosEvent(px + WIDTH / 2, py + HEIGHT / 2);
        frame();
        io.addMouseButtonEvent(0, true);
        frame();
        var panBefore = pose;
        io.addMousePosEvent(px + WIDTH / 2 + 32, py + HEIGHT / 2 - 18);
        frame();
        var panned = pose;
        near(panBefore.x() - 32. / WIDTH * 256 * panBefore.step(), panned.x());
        near(panBefore.z() + 18. / HEIGHT * 256 * panBefore.step(), panned.z());
        io.addMouseButtonEvent(0, false);
        frame();
        int afterRelease = changes;
        io.addMousePosEvent(px + WIDTH / 2 + 64, py + HEIGHT / 2 - 36);
        frame();
        require(pose.equals(panned) && changes == afterRelease, "Released drag moved pose");
        io.addMousePosEvent(900, 700);
        frame();
        io.addMouseWheelEvent(0, 3);
        frame();
        require(pose.equals(panned) && changes == afterRelease, "Outside wheel moved pose");
        pose = new NoiseImageNavigation(pose.x(), pose.z(), NoiseImageNavigation.MIN_STEP);
        io.addMousePosEvent(px + WIDTH * .25f, py + HEIGHT * .35f);
        frame();
        int beforeClamp = changes;
        var clamped = pose;
        io.addMouseWheelEvent(0, 1);
        frame();
        require(pose.equals(clamped) && changes == beforeClamp, "Saturated zoom requested work");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Navigation changed authored state");
        Files.writeString(
            out.resolve("image-input-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("before", before),
                    Map.entry("zoomed", zoomed),
                    Map.entry("u", u),
                    Map.entry("v", v),
                    Map.entry("panBefore", panBefore),
                    Map.entry("panned", panned),
                    Map.entry("released", true),
                    Map.entry("outsideIgnored", true),
                    Map.entry("saturatedNoWork", true),
                    Map.entry("viewOnly", true))));
        System.out.println(
            "IMAGE_INPUT_NATIVE_PASS wheel anchor / drag / release / outside wheel / saturation /"
                + " view-only");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(30, 30, ImGuiCond.Always);
    ImGui.setNextWindowSize(700, 450, ImGuiCond.Always);
    ImGui.begin(
        "2D input surface",
        ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoScrollbar);
    px = ImGui.getCursorScreenPosX();
    py = ImGui.getCursorScreenPosY();
    ImGui.invisibleButton("image", WIDTH, HEIGHT);
    var next = NoiseImageInput.navigate(pose, ImGui.isItemHovered(), px, py, WIDTH, HEIGHT);
    if (!next.equals(pose)) {
      pose = next;
      changes++;
    }
    ImGui.end();
    ImGui.render();
  }

  private static void near(double a, double b) {
    require(Double.isFinite(b) && Math.abs(a - b) < 1e-10, a + " != " + b);
  }

  private static void require(boolean ok, String error) {
    if (!ok) throw new AssertionError(error);
  }
}
