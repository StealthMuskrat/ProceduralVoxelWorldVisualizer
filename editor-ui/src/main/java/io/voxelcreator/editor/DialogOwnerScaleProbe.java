package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred named modal font ownership with an unrelated caller, hidden owners and synthetic DPI.
 */
public final class DialogOwnerScaleProbe {
  private static EditorState state;
  private static DialogLayout layout;
  private static SettingsPageChrome chrome;
  private static String owner, title;
  private static boolean opening, closed, ownerVisible, checkInactive, samplePopup;
  private static int width = 1200, height = 900;
  private static UiDpiPolicy.Frame dpi = new UiDpiPolicy.Frame(1, 1, 1);
  private static final Map<String, Float> fonts = new LinkedHashMap<>();
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      inactive = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(TraceWorkflowProbe::fixture), "Fixture rejected");
        state.document.save(out.resolve("source-project.json"));
        state.user.save(out.resolve("preferences-before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private-field";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var originalScales = new LinkedHashMap<>(state.user.appearance.windowScales);
        float originalGlobal = state.user.appearance.globalScale;
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1},
            owners = {.5f, 2.5f, 1, .5f, 2.5f, 1.5f},
            callers = {2.5f, .5f, 2.5f, 1, .5f, 2.5f},
            contents = {1, 1, 2, 2, 2, 3},
            framebuffers = {1, 1, 1, 2, 1, 2};
        for (var named : Arrays.asList("Properties", "Find", "Resources", "Visualizers", null)) {
          owner = named;
          title = "Dialog owner / " + Objects.requireNonNullElse(owner, "none");
          layout = new DialogLayout(state, owner, 620, 420);
          chrome = new SettingsPageChrome(2);
          opening = true;
          closed = false;
          samplePopup = true;
          Map<String, Integer> ids = null;
          for (int i = 0; i < sizes.length; i++) {
            width = sizes[i][0];
            height = sizes[i][1];
            ownerVisible = owner != null && (i == 0 || i == 2);
            state.user.appearance.globalScale = globals[i];
            state.user.appearance.windowScales.put("Logs", callers[i]);
            if (owner != null) state.user.appearance.windowScales.put(owner, owners[i]);
            dpi = new UiDpiPolicy.Frame(contents[i], framebuffers[i], globals[i]);
            var preferences = ProjectIO.JSON.valueToTree(state.user);
            frames(6);
            float expected =
                13
                    * globals[i]
                    * (owner == null ? callers[i] : owners[i])
                    * contents[i]
                    / framebuffers[i];
            float caller = 13 * globals[i] * callers[i] * contents[i] / framebuffers[i];
            for (var key : List.of("modal", "body", "child", "popup"))
              require(
                  close(fonts.get(key), expected),
                  "Wrong owned font: " + title + " / " + key + " / " + fonts);
            require(
                close(fonts.get("caller-before"), caller)
                    && close(fonts.get("caller-after"), caller),
                "Modal changed its caller's font");
            if (ownerVisible)
              require(
                  close(fonts.get("owner-window"), expected),
                  "Dialog disagreed with its named owner");
            require(
                preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
                "Drawing materialized owner preferences");
            if (ids == null) ids = chrome.actionIds();
            require(
                ids.equals(chrome.actionIds()), "Owner/font changes replaced native action IDs");
            cases.add(
                Map.ofEntries(
                    Map.entry("owner", Objects.requireNonNullElse(owner, "none")),
                    Map.entry("scenario", i),
                    Map.entry("global", globals[i]),
                    Map.entry("ownerMultiplier", owners[i]),
                    Map.entry("callerMultiplier", callers[i]),
                    Map.entry("contentScale", contents[i]),
                    Map.entry("framebufferScale", framebuffers[i]),
                    Map.entry("ownerVisible", ownerVisible),
                    Map.entry("fonts", Map.copyOf(fonts)),
                    Map.entry("window", layout.bounds()),
                    Map.entry("body", chrome.body()),
                    Map.entry("actions", chrome.actions()),
                    Map.entry("ids", chrome.actionIds())));
          }
          samplePopup = false;
          clickCancel();
          require(closed, "Owned modal Cancel was unreachable");
          checkInactive = true;
          frame();
        }
        state.user.appearance.globalScale = originalGlobal;
        state.user.appearance.windowScales = originalScales;
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Dialog scaling changed project/private input/history");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        require(
            ProjectIO.JSON
                .readTree(out.resolve("preferences-before.json").toFile())
                .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())),
            "Dialog scaling changed persisted preferences");
        try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
          Files.writeString(
              out.resolve("source-trace.json"),
              ProjectIO.JSON.writeValueAsString(world.trace(new BlockPos(2, 0, 3))));
        }
        Files.writeString(
            out.resolve("dialog-owner-scale-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "fontSource",
                    "synthetic-dpi-and-user-scale-fixture",
                    "cases",
                    cases,
                    "inactive",
                    inactive,
                    "privatePreserved",
                    true,
                    "physicalDpiVerified",
                    false,
                    "physicalDetachedVerified",
                    false,
                    "gpuFontTextureVerified",
                    false)));
        System.out.println(
            "DIALOG_OWNER_SCALE_NATIVE_PASS named owners independent of callers / hidden-owner live"
                + " scaling / ownerless inheritance / nested fonts / closed-modal isolation");
      }
    } finally {
      UiDpi.clear();
      ImGui.destroyContext();
    }
  }

  private static void frame() {
    fonts.clear();
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setDisplayFramebufferScale(dpi.framebufferScale(), dpi.framebufferScale());
    io.setFontGlobalScale(dpi.fontGlobalScale());
    UiDpi.begin(dpi);
    try {
      ImGui.newFrame();
      if (ownerVisible) {
        ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        ImGui.begin("Logical dialog owner / " + owner);
        UiScale.window(state.user, owner);
        DialogLayout.observe(state, owner);
        fonts.put("owner-window", ImGui.getTextLineHeight());
        ImGui.text("Owner window");
        ImGui.end();
      }
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.begin("Unrelated dialog caller");
      UiScale.window(state.user, "Logs");
      fonts.put("caller-before", ImGui.getTextLineHeight());
      int root = ImGui.getID("owner-scale-root");
      if (opening) {
        layout.open(title);
        opening = false;
      }
      if (layout.begin(title)) {
        try {
          fonts.put("modal", ImGui.getTextLineHeight());
          chrome.begin("owner-scale-body");
          try {
            fonts.put("body", ImGui.getTextLineHeight());
            UiScale.beginChild("owner-scale-child", 0, 0, false);
            try {
              fonts.put("child", ImGui.getTextLineHeight());
              if (samplePopup) ImGui.openPopup("Owned nested font");
              if (samplePopup && UiScale.beginPopup("Owned nested font")) {
                try {
                  fonts.put("popup", ImGui.getTextLineHeight());
                  ImGui.text("Nested font");
                  ImGui.closeCurrentPopup();
                } finally {
                  ImGui.endPopup();
                }
              }
            } finally {
              ImGui.endChild();
            }
          } finally {
            chrome.end();
          }
          chrome.identifiedAction("keep", "Keep Open");
          if (chrome.identifiedAction("cancel", "Cancel")) {
            closed = true;
            ImGui.closeCurrentPopup();
          }
        } finally {
          ImGui.endPopup();
        }
      }
      fonts.put("caller-after", ImGui.getTextLineHeight());
      require(root == ImGui.getID("owner-scale-root"), "Owned modal leaked native scopes");
      if (checkInactive) {
        checkInactive = false;
        float before = ImGui.getTextLineHeight();
        ImGui.setNextWindowPos(44, 55, ImGuiCond.Always);
        ImGui.setNextWindowSize(211, 133, ImGuiCond.Always);
        require(
            !layout.begin(title) && before == ImGui.getTextLineHeight(),
            "Inactive modal changed native caller font");
        ImGui.begin("Following unrelated owner-scale window", ImGuiWindowFlags.NoSavedSettings);
        var rectangle =
            new DialogBounds.Rect(
                ImGui.getWindowPosX(),
                ImGui.getWindowPosY(),
                ImGui.getWindowSizeX(),
                ImGui.getWindowSizeY());
        ImGui.end();
        require(
            rectangle.equals(new DialogBounds.Rect(44, 55, 211, 133)),
            "Inactive modal consumed NextWindow geometry");
        inactive.add(
            Map.of(
                "owner",
                Objects.requireNonNullElse(owner, "none"),
                "window",
                rectangle,
                "callerFont",
                before));
      }
      ImGui.end();
      ImGui.render();
    } finally {
      UiDpi.clear();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void clickCancel() {
    var box = chrome.actions().get("cancel");
    require(box != null, "Missing owned modal Cancel");
    var io = ImGui.getIO();
    io.addMousePosEvent(box.x() + box.width() / 2, box.y() + box.height() / 2);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frames(2);
  }

  private static boolean close(Float actual, float expected) {
    return actual != null && Math.abs(actual - expected) < .001;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
