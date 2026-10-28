package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native definition: production Properties focus, keyboard repair and draft ownership. */
final class PropertyFocusProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static boolean focus;
  private static final List<String> acknowledged = new ArrayList<>();

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    acknowledged.clear();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      require(state.change(p -> fixture(state, p)), "Property focus fixture");
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      panel = new PropertyPanel(state);
      var draft = (Noise) state.draft;
      var constant = draft.processing.getFirst();
      var warp = draft.coordinateTransforms.getFirst();
      var shared = draft.sharedParameters.getFirst();
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      double before = sample();
      frames(3);

      draft.algorithm.parameters.put("value", Double.NaN);
      require(!state.applyDraft(), "Rejected source value accepted");
      var issue =
          state.issues.stream()
              .filter(d -> draft.uuid.equals(d.sourceUuid()) && d.itemUuid() == null)
              .findFirst()
              .orElseThrow();
      require(
          panel.goToProblem(EditorEvents.Target.diagnostic(state.project().project.uuid, issue)),
          "Source diagnostic route");
      focus = true;
      untilProperty(null, "algorithm/parameters/value");
      replace("7");
      require(draft.algorithm.parameters.get("value") == 7, "Source keyboard repair");
      reveal(null, "algorithm", "");
      untilProperty(null, "algorithm");
      reveal(null, "outputBinding", "");
      untilProperty(null, "outputBinding");
      reveal(null, "seedOffset", "");
      untilProperty(null, "seedOffset");
      replace("11");

      reveal(constant.uuid, "value", "/noise2D/99/processing/999/parameters/value/1");
      untilProperty(constant.uuid, "parameters/value/1");
      replace("9");
      require(
          constant.parameters.get("value").get(1).doubleValue() == 9,
          "Typed constant component repair");
      reveal(shared.uuid, "value", "/noise2D/99/sharedParameters/999/value/2");
      untilProperty(shared.uuid, "parameters/value/2");
      replace("13");
      require(shared.value.get(2).doubleValue() == 13, "Shared vector component repair");

      reveal(warp.uuid, "parameters/amplitude", "");
      untilProperty(warp.uuid, "parameters/amplitude");
      replace("2");
      reveal(warp.uuid, "parameters/warpMode", "");
      untilProperty(warp.uuid, "parameters/warpMode");
      for (String leaf :
          List.of("type", "sourceKind", "noiseGeneratorUuid", "projection", "slice")) {
        reveal(
            warp.uuid,
            "inputs/displacementX",
            "/noise2D/99/coordinateTransforms/999/inputs/displacementX/" + leaf);
        untilBinding(warp, "displacementX", leaf);
      }
      replace("3.5");
      require(
          warp.inputs.get("displacementX").slice == 3.5, "Specialized warp slice keyboard edit");
      require(state.draft == draft, "Navigation replaced draft");
      require(
          state.revision == revision
              && state.document.historyBytes() == history
              && committed.equals(ProjectIO.JSON.valueToTree(state.project())),
          "Navigation or provisional edit published document");
      require(state.applyDraft(), "Repaired focus draft did not Apply: " + state.issues);
      double after = sample();
      require(before == 2 && after == 7, "Independent constant field result");
      state.document.undo();
      require(sample() == 2, "One Undo did not restore source");
      state.document.redo();
      require(sample() == 7, "One Redo did not restore edits");
      state.document.save(out.resolve("property-focus-project.json"));
      Files.writeString(
          out.resolve("property-focus-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "focused",
                  acknowledged,
                  "before",
                  before,
                  "after",
                  after,
                  "draftPreserved",
                  true,
                  "navigationOnly",
                  true,
                  "oneUndoRedo",
                  true)));
      System.out.println(
          "PROPERTY_FOCUS_NATIVE_PASS source/typed/warp keyboard routes / private rejected draft /"
              + " one Apply-Undo-Redo");
    } finally {
      state = null;
      panel = null;
    }
  }

  private static void fixture(EditorState state, ProjectData p) {
    var n = new Noise();
    n.id = "property-focus";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 2.);
    var external = new Noise();
    external.id = "zero-displacement";
    external.algorithm.key = "CONSTANT";
    external.algorithm.parameters.put("value", 0.);
    var constant = ProcessingRegistry.create("CONSTANT", "typed-constant", 2);
    constant.resultType = "Vector2";
    constant.parameters.put("value", ProjectIO.JSON.valueToTree(new double[] {1, 2}));
    n.processing.add(constant);
    var shared = new Parameter();
    shared.id = "shared-vector";
    shared.type = "Vector3";
    shared.value = ProjectIO.JSON.valueToTree(new double[] {1, 2, 3});
    n.sharedParameters.add(shared);
    var warp = EditorCreations.transform(state.metadata, 2, "DOMAIN_WARP", "warp");
    warp.inputs.put("displacementX", Binding.noiseField(external.uuid));
    warp.inputs.put("displacementZ", Binding.noiseField(external.uuid));
    n.coordinateTransforms.add(warp);
    p.noise2D.addAll(List.of(n, external));
  }

  private static double sample() {
    var p = state.project();
    return new NoiseCompiler(p, p.project.seed).compile(p.noise2D.getFirst().uuid).sample(0, 0, 0);
  }

  private static void reveal(UUID item, String parameter, String pointer) {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, item, parameter, pointer, false)),
        "Property route unavailable");
    focus = true;
  }

  private static void untilProperty(UUID item, String parameter) {
    String key = item + ":" + parameter;
    for (int i = 0; i < 60 && !key.equals(panel.probeFocusedProperty()); i++) frame();
    require(key.equals(panel.probeFocusedProperty()), "Property focus not acknowledged: " + key);
    frames(3); // Acknowledged navigation must not reactivate the next edit every frame.
    acknowledged.add(parameter);
  }

  private static void untilBinding(Item item, String name, String leaf) {
    String key = item.uuid + ":" + name + ":" + leaf;
    for (int i = 0; i < 60 && !key.equals(panel.probeFocusedBinding()); i++) frame();
    require(
        key.equals(panel.probeFocusedBinding()),
        "Specialized binding focus not acknowledged: " + key);
    frames(3);
    acknowledged.add("inputs/" + name + "/" + leaf);
  }

  private static void replace(String text) {
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
    frames(2);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    if (focus) {
      ImGui.setWindowFocus("Properties");
      focus = false;
    }
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 850, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
