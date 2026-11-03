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
        var unused = noise.processing.getLast();
        reveal(unused.uuid, "parameters/value");
        click(panel.probePinCreation(unused.uuid));
        frames(2);
        click(panel.probePinOption("parameters/value"));
        frames(3);
        showPins();
        choose("unused / value");
        click(panel.probePinControl("value"));
        type("0.125");
        require(
            unused.parameters.get("value").doubleValue() == .125
                && sample(noise, state.project()) == 5,
            "Individual literal pin did not edit the original unused item");
        reveal(add.uuid, "inputs/a");
        click(panel.probePinCreation(add.uuid));
        frames(2);
        click(panel.probePinOption("inputs/a"));
        frames(3);
        showPins();
        choose("sum / a");
        require(
            panel.probePinControl("value") == null,
            "Bound individual input exposed a misleading editable constant");
        click(panel.probePinControl("source"));
        for (int i = 0; i < 20; i++) {
          frame();
          if ((add.uuid + ":a:parameterUuid").equals(panel.probeFocusedBinding())) break;
        }
        require(
            (add.uuid + ":a:parameterUuid").equals(panel.probeFocusedBinding()),
            "Individual pin did not follow its exact named input");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Private pins published a transaction");
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Pin filtering changed preferences");
        var filter =
            new ResourceSearchIndex.Filter(
                "sum / a",
                "pinned control",
                "All",
                "All",
                null,
                null,
                false,
                ResourceSearchIndex.Mode.ID);
        require(
            new ResourceSearchIndex(state.project()).search(filter).isEmpty(),
            "Committed Find exposed a private pin before Apply");
        require(state.applyDraft(), "Pin Apply rejected");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(committed.equals(ProjectIO.JSON.valueToTree(state.project())), "One pin Undo");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One pin Redo");
        var hit = new ResourceSearchIndex(state.project()).search(filter).getFirst();
        require(
            new SearchNavigation(state).request(hit.target()) == SearchNavigation.Result.NAVIGATED
                && panel.revealSearch(hit.target()),
            "Committed pin route unavailable");
        focus = true;
        until(add.uuid + ":pin/label");
        var selected = panel.probeSelectedPin();
        require(
            selected.control.equals("inputs/a") && selected.targetUuid.equals(add.uuid),
            "Find chose the whole-item pin instead of its control");
        click(panel.probePinControl("up"));
        frames(3);
        require(panel.revealSearch(hit.target()), "Reordered pin route unavailable");
        until(add.uuid + ":pin/label");
        require(
            panel.probeSelectedPin() == selected, "Historical array position selected another pin");
        var use =
            new NoiseDraftIndex((Noise) state.draft)
                .users(add.uuid).stream()
                    .filter(value -> value.label().equals("Pin: sum / a"))
                    .findFirst()
                    .orElseThrow();
        require(
            panel.goToProblem(
                new EditorEvents.Target(
                    state.project().project.uuid,
                    state.selected,
                    use.owner(),
                    use.path(),
                    "",
                    false)),
            "Used By pin route unavailable");
        until(add.uuid + ":pin/label");
        require(panel.probeSelectedPin() == selected, "Used By chose another pin on the same item");
        click(panel.probePinControl("down"));
        frames(3);
        var draftBeforeHelp = ProjectIO.JSON.valueToTree(state.draft);
        state.helpRequests.take();
        click(panel.probePinControl("header"), 1);
        frames(3);
        var help = panel.contextHelp();
        require(help.actionCenter() != null, "Pinned Controls context Help did not open");
        click(help.actionCenter());
        require(
            state.helpRequests.take().orElseThrow().equals("help.pins"),
            "Context Help opened another topic");
        require(
            draftBeforeHelp.equals(ProjectIO.JSON.valueToTree(state.draft))
                && applied.equals(ProjectIO.JSON.valueToTree(state.project()))
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Pin discovery or Help published a private edit");
        var helpCatalog = new HelpCatalog(state.metadata);
        Files.writeString(
            out.resolve("pin-help.txt"), helpCatalog.topic("help.pins").copyText(helpCatalog));
        state.document.save(out.resolve("pin-browser-project.json"));
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("before", 2);
        report.put("after", 5);
        report.put("identitySelection", true);
        report.put("readOnlyFilter", true);
        report.put("namedInputNavigation", true);
        report.put("canonicalValue", true);
        report.put("privateDraft", true);
        report.put("oneUndoRedo", true);
        report.put("individualControls", true);
        report.put("findPinNavigation", true);
        report.put("logicalUsedBy", true);
        report.put("pinContextHelp", true);
        Files.writeString(
            out.resolve("pin-browser-native.json"), ProjectIO.JSON.writeValueAsString(report));
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

  private static void showPins() {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, null, "pinnedControls", "", false)),
        "Pin picker route unavailable");
    focus = true;
    until("null:pins");
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
    click(at, 0);
  }

  private static void click(float[] at, int button) {
    require(at != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(button, true);
    frame();
    io.addMouseButtonEvent(button, false);
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
