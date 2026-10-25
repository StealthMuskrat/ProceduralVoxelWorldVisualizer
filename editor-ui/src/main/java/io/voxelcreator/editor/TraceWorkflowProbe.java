package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

/** Real native trace clipping, shared log presentation and guarded draft navigation. */
public final class TraceWorkflowProbe {
  private static Runnable draw;

  public static void main(String[] args) throws Exception {
    var output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 1100);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(TraceWorkflowProbe::fixture), "Fixture validation");
        var source = ProjectIO.copy(state.project());
        GenerationTrace result;
        try (var world = state.engine.openWorld(new CompiledProject(source))) {
          result = world.trace(new BlockPos(2, 0, 3));
          require(
              world.getVoxelTypeId(new BlockPos(2, 0, 3)) == result.finalType(),
              "Actual voxel/trace agreement");
        }
        var real = new TraceSnapshot(source, state.revision, result);
        var repeated =
            new TraceSnapshot(
                source,
                state.revision,
                new GenerationTrace(
                    result.position(),
                    result.finalType(),
                    Collections.nCopies(2000, result.steps().getFirst())));
        var properties = new PropertyPanel(state);
        var guard = new GuardedNavigation(state, properties);
        var panel = new TracePanel(state, guard);
        currentGuard = guard;
        draw = () -> panel.draw(repeated);
        frames(4);
        int stepRows = panel.submittedSteps(), valueRows = panel.submittedRows();
        require(
            stepRows > 0 && stepRows <= 30 && valueRows > 0 && valueRows <= 30,
            "Unbounded trace rows");
        var noise = state.project().noise2D.getFirst();
        state.select("noise2D", noise.uuid);
        Object draft = state.draft;
        ((Noise) draft).id = " invalid ";
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes();
        panel.filter("Compare threshold");
        frames(3);
        require(panel.submittedRows() == 1, "Exact trace comparison filter");
        click(panel.goToCenter());
        require(guard.pending(), "Trace link bypassed pending draft");
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(
            !guard.pending()
                && state.draft == draft
                && committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Escape changed private or committed state");
        click(panel.goToCenter());
        frames(2);
        click(guard.button("APPLY"));
        frames(2);
        require(
            guard.pending() && state.draft == draft && history == state.document.historyBytes(),
            "Invalid guard Apply lost draft or created history");
        click(guard.button("CANCEL"));
        frames(2);
        ((Noise) draft).id = "renamed-field";
        click(panel.goToCenter());
        frames(2);
        click(guard.button("APPLY"));
        frames(2);
        require(
            !guard.pending() && state.selected.equals(source.worldGenerator.layers.getFirst().uuid),
            "Valid guard Apply did not reveal layer default");
        require(
            state.project().noise2D.getFirst().id.equals("renamed-field"),
            "Guard did not apply exact source draft");
        draw = properties::draw;
        frames(3);
        state.document.undo();
        state.revision++;
        state.refreshDraft();
        require(
            state.project().noise2D.getFirst().id.equals("field"),
            "Navigation introduced an extra undo entry");
        long event = state.publishTrace(real);
        require(state.traces.get(event).orElseThrow() == real, "Retained trace identity");
        var logs = new DiagnosticsPanel(state, guard::request);
        var shared = new TracePanel(state, guard);
        logs.tracePanel(shared);
        logs.reveal(event);
        draw = () -> logs.drawLogs(new ImBoolean(true));
        frames(4);
        require(
            shared.submittedRows() > 0 && shared.submittedRows() <= 30,
            "Logs did not render shared bounded trace view");
        new ProjectIO().save(output.resolve("trace-project.json"), state.project());
        Files.writeString(
            output.resolve("trace-result.json"), ProjectIO.JSON.writeValueAsString(result));
        Files.writeString(output.resolve("trace-details.txt"), real.details());
        Files.writeString(
            output.resolve("trace-workflow-reference.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "syntheticSteps",
                    2000,
                    "stepRows",
                    stepRows,
                    "valueRows",
                    valueRows,
                    "filteredRows",
                    1,
                    "worldValue",
                    result.finalType(),
                    "primaryField",
                    result.steps().getFirst().field(),
                    "guardedNavigation",
                    true,
                    "logRows",
                    shared.submittedRows())));
        System.out.println(
            "TRACE_WORKFLOW_NATIVE_PASS 2000 synthetic presentation summaries / real world replay /"
                + " exact comparison filter / Escape Cancel / rejected and successful Apply / one"
                + " Undo / shared Logs trace / unchanged generation");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  static void fixture(ProjectData p) {
    p.project.minY = -8;
    p.project.maxY = 8;
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 2.);
    var transform = new Item();
    transform.id = "translate";
    transform.kind = "TRANSLATE";
    transform.resultType = "Coordinates2D";
    transform.parameters.put("x", ProjectIO.JSON.valueToTree(1));
    n.coordinateTransforms.add(transform);
    var expression = io.voxelcreator.core.ProcessingRegistry.create("EXPRESSION", "expression", 2);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("source+2*x+z"));
    var multiply = io.voxelcreator.core.ProcessingRegistry.create("MULTIPLY", "multiply", 2);
    multiply.inputs.put("a", Binding.earlier(expression.uuid));
    multiply.inputs.put("b", Binding.constant(2));
    n.processing.addAll(List.of(expression, multiply));
    n.outputBinding = Binding.earlier(multiply.uuid);
    p.noise2D.add(n);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = 10;
    for (int i = 0; i < 2; i++) {
      var op = new Operation();
      op.id = i == 0 ? "selected" : "rejected";
      op.noiseGeneratorUuid = n.uuid;
      op.interpretation = "SELECTION";
      if (i == 1) op.overrides.put("threshold", ProjectIO.JSON.valueToTree(30));
      layer.operations.add(op);
    }
    p.worldGenerator.layers.add(layer);
  }

  private static void require(boolean b, String message) {
    if (!b) throw new AssertionError(message);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static GuardedNavigation currentGuard;

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1300, 1000, ImGuiCond.Always);
    ImGui.begin("Trace Workflow");
    draw.run();
    ImGui.end();
    if (currentGuard != null) currentGuard.draw();
    ImGui.render();
  }

  private static void click(float[] c) {
    require(c != null, "Native action was not submitted");
    var io = ImGui.getIO();
    io.addMousePosEvent(c[0], c[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }
}
