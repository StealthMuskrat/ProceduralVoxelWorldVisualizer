package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Deferred real project/import modal fit and wheel-to-Cancel in small, scaled ImGui work areas. */
public final class DialogLayoutProbe {
  private static EditorState state;
  private static ProjectFileDialog files;
  private static ResourceImportPanel imports;
  private static int width = 480, height = 320;
  private static boolean importing;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      var user = new UserConfig();
      user.appearance.globalScale = 2.5f;
      try (var editor = new EditorState(user, false)) {
        state = editor;
        require(state.change(p -> p.noise2D.add(noise("field"))), "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var privateDraft = (Noise) state.draft;
        privateDraft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        files =
            new ProjectFileDialog(
                state,
                () -> {
                  throw new AssertionError("Replacement during Cancel");
                },
                () -> {
                  throw new AssertionError("Exit during Cancel");
                });
        imports = new ResourceImportPanel(state, () -> false, () -> {});
        var cases = new ArrayList<Map<String, Object>>();
        frames(3);
        files.workflow.request(ProjectFileWorkflow.Intent.OPEN);
        frames(5);
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.DRAFT,
            "Expected unapplied-draft guard");
        cases.add(record("draft-480", files.layout));
        wheelToCancel(() -> files.probeControl("Cancel"), files.layout);
        click(files.probeControl("Cancel"));
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE,
            "Scaled guard Cancel did not close");
        width = 360;
        height = 240;
        frames(3);
        files.workflow.save(true);
        frames(5);
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.SAVE,
            "Save As did not open path dialog");
        cases.add(record("path-360", files.layout));
        Path forbidden = out.resolve("cancelled-project.pvproj.json");
        typePath(forbidden.toString());
        require(
            files.probePath().equals(forbidden.toString()),
            "Narrow path field lost displayed text");
        wheelToCancel(() -> files.probeControl("Cancel"), files.layout);
        click(files.probeControl("Cancel"));
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE,
            "Narrow path Cancel did not close");
        require(!Files.exists(forbidden), "Cancel wrote a project file");
        width = 720;
        height = 480;
        user.appearance.globalScale = 1.25f;
        frames(3);
        files.workflow.request(ProjectFileWorkflow.Intent.OPEN);
        frames(5);
        cases.add(record("reopen-720", files.layout));
        wheelToCancel(() -> files.probeControl("Cancel"), files.layout);
        click(files.probeControl("Cancel"));
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE,
            "Same-kind reopen lost Cancel");
        importing = true;
        width = 360;
        height = 240;
        user.appearance.globalScale = 2.5f;
        frames(3);
        // A removed/unknown viewport must fall back without retaining or dereferencing its pointer.
        state.dialogHosts.observe("Resources", 0xfedcba98, ImGui.getFrameCount());
        var incoming = new ProjectData();
        incoming.noise2D.add(noise("incoming"));
        require(imports.open(incoming), "Import fixture rejected");
        frames(5);
        require(
            imports.layout.viewportId() == ImGui.getMainViewport().getID(),
            "Removed viewport did not fall back to main");
        cases.add(record("import-360-fallback", imports.layout));
        wheelToCancel(() -> imports.control("cancel"), imports.layout);
        click(imports.control("cancel"));
        require(!imports.pending(), "Narrow import Cancel did not close");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset"))
                && state.engine.profiler().current().isEmpty(),
            "Modal sizing/Cancel changed private/project state");
        Files.writeString(
            out.resolve("dialog-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("cases", cases),
                    Map.entry("projectPreserved", true),
                    Map.entry("cancelReachableByWheel", true),
                    Map.entry("sameKindReopen", true),
                    Map.entry("removedHostFallback", true),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("physicalDpiVerified", false))));
        System.out.println(
            "DIALOG_LAYOUT_NATIVE_PASS scaled draft/path/import bounds / wheel-to-Cancel / reopen /"
                + " removed-host fallback; physical detached/DPI acceptance separate");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Noise noise(String id) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .125);
    return noise;
  }

  private static Map<String, Object> record(String name, DialogLayout layout) {
    var bounds = layout.bounds();
    require(bounds != null, "No measured modal bounds for " + name);
    var viewport = ImGui.getMainViewport();
    var work =
        new DialogBounds.Rect(
            viewport.getWorkPosX(),
            viewport.getWorkPosY(),
            viewport.getWorkSizeX(),
            viewport.getWorkSizeY());
    require(
        DialogBounds.contains(DialogBounds.inset(work), bounds),
        "Modal outside current work area: " + name + " " + bounds);
    return Map.of(
        "name",
        name,
        "bounds",
        bounds,
        "workArea",
        work,
        "scale",
        state.user.appearance.globalScale);
  }

  private static void wheelToCancel(Supplier<float[]> control, DialogLayout layout) {
    for (int attempt = 0; attempt < 16; attempt++) {
      var point = control.get();
      var bounds = layout.bounds();
      if (point != null
          && point[0] >= bounds.x() + 4
          && point[0] < bounds.x() + bounds.width() - 4
          && point[1] >= bounds.y() + ImGui.getFrameHeight()
          && point[1] < bounds.y() + bounds.height() - 8) return;
      var io = ImGui.getIO();
      io.addMousePosEvent(bounds.x() + bounds.width() - 4, bounds.y() + bounds.height() / 2);
      frame();
      io.addMouseWheelEvent(0, -30);
      frames(3);
    }
    throw new AssertionError("Cancel was not reachable by native modal wheel scrolling");
  }

  private static void typePath(String value) {
    click(files.probeControl("Path"));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
  }

  private static void click(float[] point) {
    require(point != null, "Missing Cancel control");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin(
        "Dialog layout fixture host",
        ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoTitleBar);
    ImGui.setWindowFontScale(1);
    if (importing) imports.draw();
    else files.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
