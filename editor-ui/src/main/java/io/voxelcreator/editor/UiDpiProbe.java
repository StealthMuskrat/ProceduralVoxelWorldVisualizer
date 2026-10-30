package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred dense-atlas/font-scope workflow using synthetic frame DPI, no GLFW or GL renderer. */
public final class UiDpiProbe {
  private static EditorState state;
  private static UiDpiPolicy.Frame frameDpi;
  private static final Map<String, Float> sizes = new LinkedHashMap<>();
  private static float[] combo;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(2000, 1600);
      io.setDeltaTime(1f / 60);
      var atlas = UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "field";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var values = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var cases = new ArrayList<Map<String, Object>>();
        measure(cases, "standard", 1, 1, 1, 1);
        measure(cases, "pixel-dpi-2", 2, 1, 1.25f, 1.5f);
        measure(cases, "retina-2", 2, 2, 1.25f, 1.5f);
        measure(cases, "pixel-dpi-3", 3, 1, 1, 1);
        measure(cases, "user-product-6.25", 2, 1, 2.5f, 2.5f);
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && values.equals(ProjectIO.JSON.valueToTree(draft))
                && state.draft == draft
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.seedInputs.text("seedOffset").equals("-"),
            "DPI scopes changed authoring state");
        Files.writeString(
            out.resolve("ui-dpi-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "dpiSource",
                    "synthetic",
                    "atlas",
                    atlas,
                    "cases",
                    cases,
                    "projectPreserved",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false,
                    "gpuTextureVerified",
                    false)));
        System.out.println(
            "UI_DPI_NATIVE_PASS dense logical atlas / synthetic DPI-frame and nested"
                + " child/combo/popup metrics; GPU/physical DPI separate");
      }
    } finally {
      UiDpi.clear();
      ImGui.destroyContext();
    }
  }

  private static void measure(
      List<Map<String, Object>> cases,
      String name,
      float content,
      float framebuffer,
      float global,
      float window) {
    frameDpi = new UiDpiPolicy.Frame(content, framebuffer, global);
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Resources", window);
    var preferences = ProjectIO.JSON.valueToTree(state.user);
    sizes.clear();
    frames(4);
    click(combo);
    frames(3);
    require(sizes.containsKey("combo"), "Native combo did not open");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(2);
    popup = true;
    frames(4);
    popup = false;
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(2);
    float expected = UiFontAtlas.LOGICAL_SIZE * global * window * content / framebuffer;
    for (String key : List.of("root", "child-1", "child-2", "child-3", "combo", "popup")) {
      var measured = sizes.get(key);
      require(
          measured != null && Math.abs(measured - expected) < .001,
          "Wrong font size: " + name + " / " + key);
    }
    require(
        preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
        "Runtime DPI mutated preferences");
    cases.add(
        Map.of(
            "name",
            name,
            "mainContentScale",
            content,
            "framebufferScale",
            framebuffer,
            "userGlobal",
            global,
            "windowMultiplier",
            window,
            "fontGlobalScale",
            frameDpi.fontGlobalScale(),
            "fontSizes",
            Map.copyOf(sizes)));
  }

  private static boolean popup;

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplayFramebufferScale(frameDpi.framebufferScale(), frameDpi.framebufferScale());
    io.setFontGlobalScale(frameDpi.fontGlobalScale());
    UiDpi.begin(frameDpi);
    try {
      ImGui.newFrame();
      ImGui.setNextWindowPos(8, 8, ImGuiCond.Always);
      ImGui.setNextWindowSize(1984, 1584, ImGuiCond.Always);
      ImGui.begin("DPI composition fixture");
      UiScale.window(state.user, "Resources");
      sizes.put("root", ImGui.getFontSize());
      ImGui.text("Root");
      for (int i = 1; i <= 3; i++) {
        UiScale.beginChild("level-" + i, 0, 0, true);
        sizes.put("child-" + i, ImGui.getFontSize());
        ImGui.text("Child " + i);
      }
      if (UiScale.beginCombo("Mode", "Current")) {
        sizes.put("combo", ImGui.getFontSize());
        ImGui.selectable("Value");
        ImGui.endCombo();
      }
      combo =
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          };
      if (popup) ImGui.openPopup("Nested Font Popup");
      if (UiScale.beginPopup("Nested Font Popup")) {
        sizes.put("popup", ImGui.getFontSize());
        ImGui.text("Popup");
        ImGui.endPopup();
      }
      for (int i = 0; i < 3; i++) ImGui.endChild();
      ImGui.end();
      ImGui.render();
    } finally {
      UiDpi.clear();
    }
  }

  private static void click(float[] point) {
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
