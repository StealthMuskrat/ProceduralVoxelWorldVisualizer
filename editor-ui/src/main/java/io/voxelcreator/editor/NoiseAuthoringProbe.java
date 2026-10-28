package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.editor.NoiseDraftIndex.Section;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Real native bounded authoring and dependency-resolution reference, with explicit fixture data.
 */
public final class NoiseAuthoringProbe {
  private static Runnable draw;

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the isolated output directory");
    var output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1500, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(NoiseAuthoringProbe::fixture), "Fixture validation");
        UUID resource = state.project().noise2D.getFirst().uuid,
            external = state.project().noise2D.getLast().uuid;
        state.select("noise2D", resource);
        var draft = (Noise) state.draft;
        UUID parameter = draft.sharedParameters.getFirst().uuid,
            multiply = draft.processing.getLast().uuid;
        var index = new NoiseDraftIndex(draft);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var browser = new NoiseItemBrowser();
        draw =
            () -> {
              for (int position : browser.draw(draft.uuid, index, Section.PROCESSING, null)) {
                if (ImGui.treeNode("Selected item")) {
                  ImGui.text(draft.processing.get(position).id);
                  ImGui.treePop();
                }
              }
            };
        frames(4);
        int itemRows = browser.submitted();
        require(itemRows > 0 && itemRows <= 30, "Unbounded ordered summary");
        click(browser.filterCenter());
        io.addInputCharactersUTF8("multiply");
        frames(3);
        require(browser.submitted() == 1, "Item filter lost its exact match");
        click(browser.center(multiply));
        frames(2);
        var picker = new DraftChoicePicker();
        var callbacks = new AtomicInteger();
        var chosen = new AtomicReference<UUID>();
        var choices = index.choices("parameter", multiply, "Scalar");
        draw =
            () ->
                picker.draw(
                    "Shared parameter",
                    null,
                    choices,
                    null,
                    value -> {
                      chosen.set(UUID.fromString(value));
                      callbacks.incrementAndGet();
                    });
        picker.probeOpen();
        frames(4);
        int parameterRows = picker.submitted();
        require(parameterRows > 0 && parameterRows <= 30, "Unbounded parameter choices");
        io.addInputCharactersUTF8("parameter-01999");
        frames(3);
        require(picker.submitted() == 1, "Typed parameter filter");
        UUID last = draft.sharedParameters.getLast().uuid;
        click(picker.option(last.toString()));
        frames(2);
        require(callbacks.get() == 1 && last.equals(chosen.get()), "Actual typed UUID selection");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && history == state.document.historyBytes()
                && revision == state.revision,
            "Browsing changed committed state");

        var cancel =
            new NoiseEditDialog(
                state, draft, Request.simple(Action.DELETE_PARAMETER, parameter), ignored -> {});
        var closed = new AtomicBoolean();
        draw =
            () -> {
              if (cancel.draw()) closed.set(true);
            };
        frames(3);
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        draw = () -> {};
        frame();
        require(closed.get(), "Native Escape did not close the resolution dialog");
        require(
            draft.sharedParameters.size() == 2000
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Cancel mutated parent or draft");
        var dialog =
            new NoiseEditDialog(
                state, draft, Request.simple(Action.DELETE_PARAMETER, parameter), ignored -> {});
        dialog.choose(Resolution.INLINE_PARAMETER, null);
        draw = dialog::draw;
        frames(4);
        click(dialog.applyCenter());
        draw = () -> {};
        frames(2);
        require(
            draft.sharedParameters.size() == 1999, "Private parameter resolution did not apply");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Private Apply committed parent");
        require(state.applyDraft(), "Parent Apply");
        require(sample(state.project(), resource) == 12, "Explicit inline changed arithmetic");
        state.document.undo();
        state.revision++;
        require(
            state.project().noise2D.getFirst().sharedParameters.size() == 2000, "One parent Undo");
        state.document.redo();
        state.revision++;
        state.refreshDraft();
        require(sample(state.project(), resource) == 12, "Redo arithmetic");

        var stagedOwner = (Noise) state.draft;
        var stagedBase = ProjectIO.JSON.valueToTree(state.project());
        long stagedHistory = state.document.historyBytes(), stagedRevision = state.revision;
        var focusAttempts = new AtomicInteger();
        var focusDialogClosed = new AtomicBoolean();
        var failedFocus =
            new NoiseEditDialog(
                state,
                stagedOwner,
                Request.simple(
                    Action.DELETE_PARAMETER, stagedOwner.sharedParameters.getLast().uuid),
                ignored -> {
                  focusAttempts.incrementAndGet();
                  throw new IllegalStateException();
                });
        draw =
            () -> {
              if (failedFocus.draw()) focusDialogClosed.set(true);
            };
        frames(4);
        click(failedFocus.applyCenter());
        frames(3);
        require(
            focusDialogClosed.get() && focusAttempts.get() == 1,
            "Failed focus reopened or repeated accepted private staging");
        require(
            stagedOwner.sharedParameters.size() == 1998
                && state.draft == stagedOwner
                && stagedBase.equals(ProjectIO.JSON.valueToTree(state.project()))
                && stagedHistory == state.document.historyBytes()
                && stagedRevision == state.revision,
            "Failed focus lost staging or committed the private edit");
        draw = () -> {};
        state.refreshDraft();

        var properties = new PropertyPanel(state);
        properties.goToControl(resource, multiply);
        draw = properties::draw;
        frames(4);
        int propertyRows = properties.probeItemRows();
        int builds = properties.probeIndexBuilds();
        require(
            propertyRows >= 0 && propertyRows <= 30,
            "Production Properties submitted unbounded rows");
        frames(3);
        require(
            properties.probeIndexBuilds() == builds,
            "Drawing rebuilt unchanged authoring metadata");
        Object pending = state.draft;
        ((Noise) pending).id = " invalid ";
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long pendingHistory = state.document.historyBytes();
        properties.probeOpenReference(external);
        frames(3);
        click(properties.probeReferenceButton("APPLY"));
        frames(2);
        require(
            state.draft == pending && resource.equals(state.selected),
            "Invalid Apply navigated or lost draft");
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project()))
                && pendingHistory == state.document.historyBytes()
                && state.draft == pending,
            "Guard Cancel changed document");
        state.refreshDraft();
        state.document.save(output.resolve("noise-authoring-project.json"));
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("items", 2002);
        report.put("parametersBefore", 2000);
        report.put("parametersAfter", 1999);
        report.put("itemRows", itemRows);
        report.put("propertyRows", propertyRows);
        report.put("parameterRows", parameterRows);
        report.put("filteredRows", 1);
        report.put("callbacks", callbacks.get());
        report.put("focusFailureConsumed", true);
        report.put("focusFailureCallbacks", focusAttempts.get());
        report.put("metadataBuilds", builds);
        report.put("sample", 12);
        Files.writeString(
            output.resolve("noise-authoring-reference.json"),
            ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "NOISE_AUTHORING_NATIVE_PASS 2002 ordered items / 2000 typed parameters / bounded"
                + " native widgets / actual filter and UUID choice / private dependency resolution"
                + " / Escape Cancel / one parent Undo-Redo / guarded reference navigation /"
                + " unchanged arithmetic");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var n = new Noise();
    n.id = "authoring";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 1.);
    for (int i = 0; i < 2000; i++) {
      var param = new Parameter();
      param.id = String.format(Locale.ROOT, "parameter-%05d", i);
      param.value = ProjectIO.JSON.valueToTree(i == 0 ? 2. : i);
      n.sharedParameters.add(param);
      var item =
          ProcessingRegistry.create("CONSTANT", String.format(Locale.ROOT, "unused-%05d", i));
      item.enabled = false;
      n.processing.add(item);
    }
    var add = ProcessingRegistry.create("ADD", "add");
    var shared = Binding.constant(0);
    shared.sourceKind = "Shared Parameter";
    shared.parameterUuid = n.sharedParameters.getFirst().uuid;
    add.inputs.put("b", shared);
    var multiply = ProcessingRegistry.create("MULTIPLY", "multiply");
    multiply.inputs.put("a", Binding.earlier(add.uuid));
    multiply.inputs.put("b", Binding.constant(4));
    n.processing.addAll(List.of(add, multiply));
    n.outputBinding = Binding.earlier(multiply.uuid);
    var pin = new Pin();
    pin.targetUuid = n.sharedParameters.getFirst().uuid;
    pin.label = "Gain";
    pin.group = "Common";
    n.pinnedControls.add(pin);
    var itemPin = new Pin();
    itemPin.targetUuid = add.uuid;
    itemPin.label = "Add settings";
    itemPin.group = "Common";
    n.pinnedControls.add(itemPin);
    p.noise2D.add(n);
    var external = new Noise();
    external.id = "external";
    external.algorithm.key = "CONSTANT";
    external.algorithm.parameters.clear();
    external.algorithm.parameters.put("value", 7.);
    p.noise2D.add(external);
  }

  private static double sample(ProjectData p, UUID uuid) {
    return new NoiseCompiler(p, 0).compile(uuid).sample(-17, 0, 23);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 850, ImGuiCond.Always);
    ImGui.begin("Noise Authoring Reference");
    draw.run();
    ImGui.end();
    ImGui.render();
  }

  private static void click(float[] center) {
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }
}
