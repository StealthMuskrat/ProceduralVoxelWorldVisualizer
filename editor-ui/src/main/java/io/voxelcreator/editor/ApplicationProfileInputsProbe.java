package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production profile text/navigation/write-retry workflow; no normal-startup fixture. */
public final class ApplicationProfileInputsProbe {
  private static final String SEED = "noise2D.seedOffset";
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";
  private static final String VECTOR = "processing.DOT.input.a";
  private static ApplicationSettingsPanel panel;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        state.metadata = AppMetadata.defaults();
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "existing";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Existing project fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "pending-id";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var active = state.metadata;
        Path file = out.resolve("app-config.json");
        active.save(file);
        var originalFile = Files.readAllBytes(file);
        panel = new ApplicationSettingsPanel(state);
        show(SEED, "Creation Defaults");
        long builds = panel.rowBuilds();
        frames(6);
        require(
            panel.rowBuilds() == builds && panel.submittedRows() > 0 && panel.submittedRows() < 20,
            "Unchanged creation rows rebuilt or escaped clipping");
        click(panel.controlAt("override"));
        type(0, "-");
        require(
            !panel.applyEnabled() && panel.rawText(SEED).equals(List.of("-")), "Invalid seed lost");
        show("noise", "Presentation");
        frames(3);
        show(VALUE, "Creation Defaults");
        click(panel.controlAt("override"));
        type(0, "1e-9");
        require(!panel.applyEnabled(), "Hidden seed error did not block Apply");
        click(panel.controlAt("apply"));
        require(
            Arrays.equals(originalFile, Files.readAllBytes(file)) && state.metadata == active,
            "Hidden error published parsed fallback");
        click(panel.controlAt("invalid-value"));
        frames(3);
        require(panel.rawText(SEED).equals(List.of("-")), "Navigation reset raw seed");
        type(0, Long.toString(Long.MAX_VALUE));
        show(VECTOR, "Creation Defaults");
        click(panel.controlAt("override"));
        var before = panel.defaultValue(VECTOR);
        type(1, "-");
        type(0, "3.5");
        require(
            before.equals(panel.defaultValue(VECTOR)) && !panel.applyEnabled(),
            "Vector used an earlier parsed component");
        type(1, "2.25");
        type(2, "9");
        require(
            panel
                .defaultValue(VECTOR)
                .equals(ProjectIO.JSON.valueToTree(new double[] {3.5, 2.25, 9})),
            "Complete vector was not accepted together");
        require(panel.applyEnabled(), "Corrected values could not be applied");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click(panel.controlAt("apply"));
        frames(3);
        require(
            state.metadata == active
                && panel.rawText(SEED).equals(List.of(Long.toString(Long.MAX_VALUE)))
                && panel.rawText(VALUE).equals(List.of("1e-9"))
                && Files.readString(file.resolve("keep")).equals("original"),
            "Failed write published or replaced private input");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click(panel.controlAt("apply"));
        frames(3);
        require(
            state.metadata != active && Files.isRegularFile(file), "Corrected path retry failed");
        var saved = AppMetadata.read(file);
        require(
            saved.creationDefaults.get(SEED).longValue() == Long.MAX_VALUE
                && saved.creationDefaults.get(VALUE).doubleValue() == 1e-9
                && saved
                    .creationDefaults
                    .get(VECTOR)
                    .equals(ProjectIO.JSON.valueToTree(new double[] {3.5, 2.25, 9})),
            "Saved values lost precision or components");
        require(
            EditorCreations.noise(saved, 2, "future", null).seedOffset == Long.MAX_VALUE,
            "Future seed did not use exact saved value");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && state.draft == draft
                && draft.id.equals("pending-id")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Application profile modified project/history/private Properties");
        require(
            new NoiseCompiler(state.project(), 0).compile(state.selected).sample(7, 0, -9) == .125,
            "Application profile changed existing evaluation");
        Files.writeString(
            out.resolve("creation-input-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("rawNavigation", true),
                    Map.entry("hiddenErrorBlocked", true),
                    Map.entry("vectorAtomic", true),
                    Map.entry("writeRetry", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("privateSeedPreserved", true),
                    Map.entry("cachedRows", true),
                    Map.entry("submittedRows", panel.submittedRows()),
                    Map.entry("exactSeed", Long.MAX_VALUE),
                    Map.entry("tiny", 1e-9),
                    Map.entry("vector", List.of(3.5, 2.25, 9.)))));
        System.out.println(
            "APPLICATION_PROFILE_INPUT_NATIVE_PASS raw navigation / hidden errors / vector /"
                + " precision / write retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void show(String id, String section) {
    panel.showControl(id, section);
    frames(4);
  }

  private static void type(int component, String text) {
    click(panel.controlAt("value-" + component));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(float[] point) {
    require(point != null, "Missing application profile control");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 900, ImGuiCond.Always);
    if (ImGui.begin("Application Settings")) panel.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
