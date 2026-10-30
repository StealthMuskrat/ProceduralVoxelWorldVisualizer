package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.editor.ProfileCaptureInputs.Field;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred real Profiler sections/raw-input/export workflow, without GLFW or GPU instances. */
public final class ProfileLayoutProbe {
  private static EditorState state;
  private static ProfilePanel panel;
  private static final ImBoolean open = new ImBoolean(true);
  private static int width = 1400, height = 1200;
  private static DialogBounds.Rect window;
  private static final Viewports.StreamingStatistics EMPTY =
      new Viewports.StreamingStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, false);

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "committed";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        byte[] projectBytes = Files.readAllBytes(projectFile);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        panel =
            new ProfilePanel(
                state,
                ignored -> {
                  throw new AssertionError("Unexpected navigation");
                });
        panel.probeWorkControls();
        var original = state.engine.profiler().start(new Profiler.Options(60000, 2048, 512, 256));
        var scope = state.engine.profiler().scope(7, state.project().project.uuid, null);
        for (int i = 0; i < 200; i++)
          scope.gauge(
              Profiler.Kind.QUEUE_DEPTH,
              Profiler.Unit.COUNT,
              i,
              i == 37 ? "needle###雪" : "row-" + i);
        state.engine.profiler().stop();
        show("Measurements");
        int unfiltered = panel.submittedRows();
        float unfilteredHeight = panel.seriesBounds().height(),
            unfilteredStride = panel.rowStride();
        require(unfiltered > 0 && unfiltered < 200, "Series list was not clipped");
        click("filter");
        type("needle");
        var rowIds = panel.rowIds();
        require(rowIds.size() == 1 && panel.submittedRows() == 1, "Actual series filter failed");
        click("prepare-export");
        frames(3);
        var review = panel.exportReview();
        var frozen = review.snapshot();
        require(frozen.samples().size() == 200, "Export review used filtered rows");
        var target = out.resolve("reviewed.json");
        click("export-path");
        type(target.toString());
        show("Capture & runtime");
        limit(Field.DURATION, "+0060000");
        limit(Field.SAMPLES, "+0002048");
        limit(Field.SERIES, "512");
        limit(Field.STRIDE, "-");
        var raw = panel.captureText();
        var feedback = panel.captureFeedback();
        // The same entry point used by Debug must reject invalid hidden limits without replacement.
        panel.start();
        frames(4);
        require(
            state.engine.profiler().current().orElseThrow() == original
                && panel.exportReview() == review
                && review.snapshot() == frozen,
            "Rejected next-capture input replaced a capture or review");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1200}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1}, multiplier = {1, 1, 1, 1, 2.5f, 1};
        var ids = new HashMap<String, Map<String, Integer>>();
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("Profiler", multiplier[i]);
          for (String section : ProfilePanel.SECTIONS) {
            show(section);
            if (section.equals("Capture & runtime")) {
              click("problem");
              frames(5);
            }
            require(
                DialogBounds.contains(window, panel.layout.body())
                    && DialogBounds.contains(window, panel.selector()),
                "Profiler body/header outside client");
            for (var action : panel.layout.actions().values())
              require(DialogBounds.contains(window, action), "Profiler action outside client");
            if (!ids.containsKey(section)) ids.put(section, panel.fieldIds());
            require(
                ids.get(section).equals(panel.fieldIds()),
                "Profiler field IDs changed during reflow");
            require(
                panel.captureText().equals(raw)
                    && panel.captureFeedback() == feedback
                    && panel.exportReview() == review
                    && review.snapshot() == frozen,
                "Section/reflow changed private input or reviewed payload");
            if (section.equals("Measurements"))
              require(panel.rowIds().equals(rowIds), "Logical series ID changed during reflow");
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("scenario", i);
            receipt.put("section", section);
            receipt.put("window", window);
            receipt.put("body", panel.layout.body());
            receipt.put("selector", panel.selector());
            receipt.put("actions", panel.layout.actions());
            receipt.put("ids", panel.fieldIds());
            receipt.put("inputs", panel.inputBounds());
            receipt.put("raw", panel.captureText());
            receipt.put("rows", panel.submittedRows());
            receipt.put("rowIds", panel.rowIds());
            receipt.put("series", panel.seriesBounds());
            receipt.put("stride", panel.rowStride());
            receipt.put("globalScale", global[i]);
            receipt.put("windowScale", multiplier[i]);
            cases.add(receipt);
          }
        }
        width = 1400;
        height = 1200;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.remove("Profiler");
        show("Measurements");
        long queries = panel.rowQueries();
        frames(4);
        require(queries == panel.rowQueries(), "Stopped projection was rebuilt");
        click("problem");
        frames(5);
        type("1");
        click("start");
        frames(3);
        var replacement = state.engine.profiler().current().orElseThrow();
        require(
            replacement != original
                && replacement
                    .snapshot()
                    .options()
                    .equals(new Profiler.Options(60000, 2048, 512, 1))
                && panel.exportReview() == review,
            "Corrected capture start failed or changed export review");
        click("stop");
        click("clear");
        frames(3);
        require(
            panel.displayedSnapshot() == null
                && panel.exportReview() == review
                && review.available(),
            "Clear consumed an independent export review");
        show("Export");
        click("export-path");
        type(projectFile.toString());
        click("export-json");
        require(
            review.available() && Arrays.equals(projectBytes, Files.readAllBytes(projectFile)),
            "Protected project export was not rejected");
        click("export-path");
        type(target.toString());
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep"), "original");
        click("export-json");
        require(
            review.available() && Files.readString(target.resolve("keep")).equals("original"),
            "Failed export lost review or earlier content");
        width = 360;
        height = 640;
        frames(5);
        Files.delete(target.resolve("keep"));
        Files.delete(target);
        click("export-json");
        require(!review.available(), "Accepted retry was not consumed");
        byte[] accepted = Files.readAllBytes(target);
        click("export-csv");
        require(
            Arrays.equals(accepted, Files.readAllBytes(target)), "Consumed review exported again");
        new ProfileExportSession(frozen)
            .write(out.resolve("reviewed.csv").toString(), true, projectFile);
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.engine == engine
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Profiler changed document/private/runtime ownership");
        Files.writeString(
            out.resolve("profile-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("unfilteredRows", unfiltered),
                    Map.entry("unfilteredHeight", unfilteredHeight),
                    Map.entry("unfilteredStride", unfilteredStride),
                    Map.entry("invalidStartPreservedCapture", true),
                    Map.entry("correctedStart", true),
                    Map.entry("reviewSurvivedClear", true),
                    Map.entry("protectedProject", true),
                    Map.entry("failedExportRetry", true),
                    Map.entry("acceptedConsumed", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("cachedRows", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PROFILE_LAYOUT_NATIVE_PASS exact limits / persistent actions / stable series / owned"
                + " export retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void show(String section) {
    panel.section(section);
    frames(7);
  }

  private static void limit(Field field, String text) {
    click(field.name());
    type(text);
  }

  private static void click(String key) {
    float[] at = panel.probeWorkControl(key);
    require(at != null, "Missing control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    panel.draw(open, EMPTY);
    window = panel.windowLayout.bounds();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
