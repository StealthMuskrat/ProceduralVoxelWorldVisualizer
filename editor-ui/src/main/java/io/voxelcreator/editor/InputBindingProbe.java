package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Full production Properties input selection, diagnostic focus and keyboard-only binding repair.
 */
final class InputBindingProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static boolean focus;
  private static final List<String> selections = new ArrayList<>();
  private static final List<String> focused = new ArrayList<>();

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Input binding window focus");
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      require(state.change(InputBindingProbe::fixture), "Input binding fixture");
      UUID id = state.project().noise2D.getFirst().uuid;
      state.select("noise2D", id);
      panel = new PropertyPanel(state);
      Noise draft = (Noise) state.draft;
      Item first = draft.processing.getFirst(),
          second = draft.processing.getLast(),
          transform = draft.coordinateTransforms.getFirst();
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      double before = sample();
      require(before == 5, "Initial binding arithmetic");
      first.inputs.get("bias").value = ProjectIO.JSON.valueToTree("broken");
      frames(3);
      require(!state.applyDraft(), "Invalid binding accepted");
      var diagnostic =
          state.issues.stream()
              .filter(d -> d.itemUuid() != null && d.itemUuid().equals(first.uuid))
              .findFirst()
              .orElseThrow();
      var diagnosticTarget =
          EditorEvents.Target.diagnostic(state.project().project.uuid, diagnostic);
      require(
          diagnosticTarget.parameter().equals("inputs/bias"),
          "Diagnostic lost named input: " + diagnosticTarget);
      require(panel.goToProblem(diagnosticTarget), "Rejected-draft diagnostic target");
      focus = true;
      untilFocused(first, "bias", "value");
      frames(3);
      replace("3.25");
      require(
          first.inputs.get("bias").value.doubleValue() == 3.25, "Focused binding keyboard repair");
      choose(first, "extra");
      frames(8);
      require(panel.probeInputSelection(first).equals("extra"), "Navigation pinned old input");
      selections.add("first/" + panel.probeInputSelection(first));

      reveal(second, "beta", "");
      untilFocused(second, "beta", "value");
      choose(second, "alpha");
      frames(5);
      require(
          panel.probeInputSelection(second).equals("alpha"), "Second input choice not retained");
      selections.add("second/" + panel.probeInputSelection(second));
      panel.goToControl(id, first.uuid);
      frames(5);
      require(
          panel.probeInputSelection(first).equals("extra"),
          "Another item overwrote first selection");
      selections.add("first/" + panel.probeInputSelection(first));
      panel.goToControl(id, second.uuid);
      frames(5);
      require(
          panel.probeInputSelection(second).equals("alpha"),
          "Item return lost independent selection");
      selections.add("second/" + panel.probeInputSelection(second));

      for (String field : List.of("type", "sourceKind")) {
        reveal(first, "bias", "/noise2D/0/processing/0/inputs/bias/" + field);
        untilFocused(first, "bias", field);
      }
      reveal(first, "flag", "");
      untilFocused(first, "flag", "value");
      reveal(first, "shared", "");
      untilFocused(first, "shared", "parameterUuid");
      reveal(second, "earlier", "");
      untilFocused(second, "earlier", "itemUuid");
      reveal(second, "ambient", "");
      untilFocused(second, "ambient", "ambientKey");
      reveal(first, "reference", "");
      untilFocused(first, "reference", "noiseGeneratorUuid");
      for (String field : List.of("projection", "slice")) {
        reveal(first, "reference", "/noise2D/0/processing/0/inputs/reference/" + field);
        untilFocused(first, "reference", field);
      }

      reveal(transform, "x", "/noise2D/0/coordinateTransforms/0/inputs/x/value");
      untilFocused(transform, "x", "value");
      frames(3);
      replace("4");
      require(transform.inputs.get("x").value.doubleValue() == 4, "Transform numeric focus repair");
      reveal(
          transform, "coordinates", "/noise2D/0/coordinateTransforms/0/inputs/coordinates/value/1");
      untilFocused(transform, "coordinates", "value/1");
      frames(3);
      replace("8");
      require(
          transform.inputs.get("coordinates").value.get(0).doubleValue() == 0
              && transform.inputs.get("coordinates").value.get(1).doubleValue() == 8,
          "Coordinate component focus selected wrong field");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && state.revision == revision
              && state.document.historyBytes() == history
              && state.draft == draft,
          "Navigation or private repair committed state");
      var foreign =
          new EditorEvents.Target(UUID.randomUUID(), id, first.uuid, "inputs/bias", "", false);
      require(
          !panel.goToProblem(foreign) && state.draft == draft, "Foreign project route accepted");
      require(state.applyDraft(), "Repaired bindings rejected: " + state.issues);
      double after = sample();
      require(after == 26.25, "Independent transformed binding arithmetic: " + after);
      state.document.undo();
      state.revision++;
      require(sample() == 5, "Single binding Undo");
      state.document.redo();
      state.revision++;
      require(sample() == 26.25, "Single binding Redo");
      state.document.save(out.resolve("input-binding-project.json"));
      Files.writeString(
          out.resolve("input-binding-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "before",
                  before,
                  "after",
                  after,
                  "selected",
                  selections,
                  "focused",
                  focused,
                  "diagnosticInput",
                  diagnosticTarget.parameter(),
                  "draftPreserved",
                  true,
                  "navigationOnly",
                  true,
                  "oneUndoRedo",
                  true,
                  "foreignRejected",
                  true)));
      glfwHideWindow(window);
      System.out.println(
          "INPUT_BINDING_NATIVE_PASS full Properties / rejected binding diagnostic / keyboard"
              + " repair / one-shot per-item selectors / transform scalar and coordinate component"
              + " / Apply-Undo-Redo");
    }
  }

  private static void fixture(ProjectData p) {
    var n = new Noise();
    n.id = "binding-navigation";
    n.algorithm.key = "CONSTANT";
    var first = ProcessingRegistry.create("EXPRESSION", "first");
    first.parameters.put("expression", ProjectIO.JSON.valueToTree("bias+extra+x+2*z"));
    first.inputs.put("bias", Binding.constant(2));
    first.inputs.put("extra", Binding.constant(3));
    var flag = Binding.constant(0);
    flag.type = "Boolean";
    flag.value = ProjectIO.JSON.valueToTree(true);
    first.inputs.put("flag", flag);
    var parameter = new Parameter();
    parameter.id = "shared-value";
    parameter.value = ProjectIO.JSON.valueToTree(13);
    n.sharedParameters.add(parameter);
    var shared = Binding.source();
    shared.sourceKind = "Shared Parameter";
    shared.parameterUuid = parameter.uuid;
    first.inputs.put("shared", shared);
    var external = new Noise();
    external.id = "external";
    external.algorithm.key = "CONSTANT";
    var reference = Binding.source();
    reference.sourceKind = "Noise Generator Reference";
    reference.noiseGeneratorUuid = external.uuid;
    first.inputs.put("reference", reference);
    var second = ProcessingRegistry.create("EXPRESSION", "second");
    second.parameters.put("expression", ProjectIO.JSON.valueToTree("alpha+beta"));
    second.inputs.put("alpha", Binding.constant(7));
    second.inputs.put("beta", Binding.constant(11));
    second.inputs.put("earlier", Binding.earlier(first.uuid));
    var ambient = Binding.source();
    ambient.sourceKind = "Ambient Value";
    ambient.ambientKey = "x";
    second.inputs.put("ambient", ambient);
    n.processing.addAll(List.of(first, second));
    n.outputBinding = Binding.earlier(first.uuid);
    var transform = new Item();
    transform.id = "translate";
    transform.kind = "TRANSLATE";
    transform.resultType = "Coordinates2D";
    transform.inputs.put("x", Binding.constant(0));
    var coordinates = Binding.constant(0);
    coordinates.type = "Coordinates2D";
    coordinates.value = ProjectIO.JSON.valueToTree(new double[] {0, 0});
    transform.inputs.put("coordinates", coordinates);
    n.coordinateTransforms.add(transform);
    p.noise2D.addAll(List.of(n, external));
  }

  private static double sample() {
    return new NoiseCompiler(state.project(), state.project().project.seed)
        .compile(state.project().noise2D.getFirst().uuid)
        .sample(0, 0, 0);
  }

  private static void reveal(Item item, String name, String pointer) {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid,
                state.selected,
                item.uuid,
                "inputs/" + name,
                pointer,
                false)),
        "Input route unavailable");
    focus = true;
  }

  private static void untilFocused(Item item, String name, String field) {
    String key = item.uuid + ":" + name + ":" + field;
    for (int i = 0; i < 60 && !key.equals(panel.probeFocusedBinding()); i++) frame();
    require(
        key.equals(panel.probeFocusedBinding()),
        "Binding focus not acknowledged: " + key + " actual=" + panel.probeFocusedBinding());
    require(
        panel.probeBindingControl(item.uuid, name, field) != null, "Focused binding not submitted");
    focused.add(item.id + "/" + name + "/" + field);
  }

  private static void choose(Item item, String name) {
    click(panel.probeInputPicker(item.uuid));
    frames(3);
    click(panel.probeInputOption(name));
    frames(3);
  }

  private static void click(float[] center) {
    require(center != null, "Missing binding control");
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
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
