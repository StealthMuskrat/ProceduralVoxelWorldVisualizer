package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production Properties pin identity, metadata, canonical value and source workflow. */
public final class PinWorkflowProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static boolean focus;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
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
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(PinWorkflowProbe::fixture), "Fixture rejected");
        var resource = state.project().noise2D.getFirst();
        state.select("noise2D", resource.uuid);
        var noise = (Noise) state.draft;
        var parameter = noise.sharedParameters.getFirst();
        var add = noise.processing.getFirst();
        var gainPin = noise.pinnedControls.getFirst();
        var itemPin = noise.pinnedControls.get(1);
        var unusedPin = noise.pinnedControls.getLast();
        var committed = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        panel = new PropertyPanel(state);
        frames(4);
        reveal(parameter.uuid, "pin/label");
        type("Editable gain");
        require(
            gainPin.label.equals("Editable gain") && panel.probeSelectedPin() == gainPin,
            "Pin metadata focus");
        var privateBeforeFilter = ProjectIO.JSON.valueToTree(noise);
        group("group:Detailed");
        require(
            panel.probeSelectedPin() == gainPin
                && privateBeforeFilter.equals(ProjectIO.JSON.valueToTree(noise)),
            "Group filtering replaced or edited selection");
        choose("Sum");
        require(panel.probeSelectedPin() == itemPin, "Exact item pin selection");
        click(panel.probePinControl("input/a"));
        for (int i = 0; i < 20; i++) {
          frame();
          if ((add.uuid + ":a:parameterUuid").equals(panel.probeFocusedBinding())) break;
        }
        require(
            (add.uuid + ":a:parameterUuid").equals(panel.probeFocusedBinding()),
            "Pinned named input route");
        require(
            add.inputs.get("a").parameterUuid.equals(parameter.uuid),
            "Source navigation changed the binding");
        reveal(parameter.uuid, "pin/label");
        click(panel.probePinControl("source"));
        until(parameter.uuid + ":parameters/value");
        require(state.draft == noise, "Source navigation discarded draft");
        reveal(parameter.uuid, "pin/group");
        type("Common edited");
        require(gainPin.group.equals("Common edited"), "Group editing hit another pin");
        click(panel.probePinControl("value"));
        type("5");
        require(
            parameter.value.doubleValue() == 5 && sample(noise, state.project()) == 5,
            "Pinned value was copied rather than canonical");
        click(panel.probePinControl("down"));
        frames(3);
        require(
            panel.probeSelectedPin() == gainPin && noise.pinnedControls.get(1) == gainPin,
            "Pin reorder changed selected object");
        group("all");
        choose("Unused");
        require(panel.probeSelectedPin() == unusedPin, "Unused pin selection");
        click(panel.probePinControl("remove"));
        frames(3);
        require(
            noise.pinnedControls.size() == 2 && !noise.pinnedControls.contains(unusedPin),
            "Unpin changed another entry");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Private pins published a transaction");
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Pin filtering changed preferences");
        require(state.applyDraft(), "Pin Apply rejected");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(committed.equals(ProjectIO.JSON.valueToTree(state.project())), "One pin Undo");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One pin Redo");
        state.document.save(out.resolve("pin-browser-project.json"));
        Files.writeString(
            out.resolve("pin-browser-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "before",
                    2,
                    "after",
                    5,
                    "identitySelection",
                    true,
                    "readOnlyFilter",
                    true,
                    "namedInputNavigation",
                    true,
                    "canonicalValue",
                    true,
                    "privateDraft",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "PIN_BROWSER_NATIVE_PASS exact pin / groups / canonical value / source links / reorder"
                + " / unpin / Apply-Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static double sample(Noise draft, ProjectData project) {
    var copy = ProjectIO.copy(project);
    copy.noise2D.set(
        0, ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(draft), Noise.class));
    return new NoiseCompiler(copy, copy.project.seed).compile(draft.uuid).sample(0, 0, 0);
  }

  private static void fixture(ProjectData project) {
    var noise = new Noise();
    noise.id = "pinned";
    var parameter = new Parameter();
    parameter.id = "gain";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    noise.sharedParameters.add(parameter);
    var add = ProcessingRegistry.create("ADD", "sum");
    var a = new Binding();
    a.sourceKind = "Shared Parameter";
    a.parameterUuid = parameter.uuid;
    var b = new Binding();
    b.sourceKind = "Ambient Value";
    b.ambientKey = "x";
    add.inputs.put("a", a);
    add.inputs.put("b", b);
    noise.processing.add(add);
    var unused = ProcessingRegistry.create("CONSTANT", "unused");
    noise.processing.add(unused);
    noise.outputBinding = Binding.earlier(add.uuid);
    for (var values :
        List.of(
            new String[] {parameter.uuid.toString(), "Gain", "Common"},
            new String[] {add.uuid.toString(), "Sum", "Detailed"},
            new String[] {unused.uuid.toString(), "Unused", "Common"})) {
      var pin = new Pin();
      pin.targetUuid = UUID.fromString(values[0]);
      pin.label = values[1];
      pin.group = values[2];
      noise.pinnedControls.add(pin);
    }
    project.noise2D.add(noise);
  }

  private static void reveal(UUID control, String parameter) {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, control, parameter, "", false)),
        "Pin route unavailable");
    focus = true;
    until(control + ":" + parameter);
  }

  private static void until(String field) {
    for (int i = 0; i < 20; i++) {
      frame();
      if (field.equals(panel.probeFocusedProperty())) break;
    }
    require(field.equals(panel.probeFocusedProperty()), "Property focus missing: " + field);
    frames(3);
  }

  private static void group(String key) {
    click(panel.probePinControl("groups"));
    frames(2);
    click(panel.probePinOption(key));
    frames(3);
  }

  private static void choose(String label) {
    var key =
        panel.probePinRows().stream()
            .filter(row -> row.label().equals(label))
            .findFirst()
            .orElseThrow()
            .key();
    click(panel.probePinControl("picker"));
    frames(2);
    click(panel.probePinOption(key));
    frames(3);
  }

  private static void type(String text) {
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

  private static void click(float[] at) {
    require(at != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
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
    if (focus) {
      ImGui.setNextWindowFocus();
      focus = false;
    }
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
