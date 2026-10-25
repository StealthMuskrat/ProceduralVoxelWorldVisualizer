package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiCond;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Explicit isolated hierarchy reference: organizational mutations never change stage execution. */
public final class HierarchyReferenceProbe {
  public static ProjectData fixture() {
    var p = new ProjectData();
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var source = new Noise();
    source.id = "constant";
    source.dimension = 3;
    source.algorithm.key = "CONSTANT";
    source.algorithm.parameters.clear();
    source.algorithm.parameters.put("value", 1.0);
    p.noise3D.add(source);
    for (String action : List.of("SET", "CLEAR")) {
      var layer = new Layer();
      layer.id = action.toLowerCase(Locale.ROOT);
      layer.defaults.action = action;
      layer.defaults.voxelTypeUuid = type.uuid;
      var op = new Operation();
      op.id = "everywhere";
      op.noiseGeneratorUuid = source.uuid;
      op.interpretation = "DENSITY";
      layer.operations.add(op);
      p.worldGenerator.layers.add(layer);
    }
    for (String name : List.of("first", "second")) {
      var f = new Folder();
      f.id = name;
      f.rootKind = "world";
      p.folders.add(f);
    }
    var fields = new Folder();
    fields.id = "fields";
    fields.rootKind = "noise3D";
    p.folders.add(fields);
    return p;
  }

  public static void organize(ProjectData p) {
    ResourceGraph.move(
        p, List.of(p.worldGenerator.layers.get(0).uuid), "world", p.folders.get(0).uuid);
    ResourceGraph.move(
        p, List.of(p.worldGenerator.layers.get(1).uuid), "world", p.folders.get(1).uuid);
    p.project.rootOrder.put(
        "world", new ArrayList<>(List.of(p.folders.get(1).uuid, p.folders.get(0).uuid)));
    p.project.rootOrder.put(
        "noise3D", new ArrayList<>(List.of(p.noise3D.getFirst().uuid, p.folders.get(2).uuid)));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the isolated output directory");
    var output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 850);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        var fixture = fixture();
        if (!state.change(p -> ResourceGraph.assign(p, fixture)))
          throw new AssertionError(state.issues);
        var identity = PreviewIdentity.of(state.project(), 2, null);
        var original = state.project().worldGenerator.layers.stream().map(l -> l.uuid).toList();
        if (!state.change(HierarchyReferenceProbe::organize))
          throw new AssertionError(state.issues);
        if (!identity.equals(PreviewIdentity.of(state.project(), 2, null))
            || !original.equals(
                state.project().worldGenerator.layers.stream().map(l -> l.uuid).toList()))
          throw new AssertionError("Organization changed execution");
        state.document.undo();
        state.revision++;
        if (state.project().worldGenerator.layers.stream()
            .anyMatch(l -> l.parentFolderUuid != null))
          throw new AssertionError("Hierarchy edit was not one Undo");
        state.document.redo();
        state.revision++;
        var panel = new ResourcePanel(state, new PropertyPanel(state));
        panel.probeOpenFolder(state.project().folders.get(0).uuid);
        panel.probeOpenFolder(state.project().folders.get(1).uuid);
        for (int i = 0; i < 4; i++) {
          ImGui.newFrame();
          ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
          ImGui.setNextWindowSize(650, 760, ImGuiCond.Always);
          panel.draw();
          ImGui.render();
        }
        var browser = new ResourceTreeIndex(state.project());
        var world =
            browser.project(
                "world",
                Map.of(
                    state.project().folders.get(0).uuid,
                    true,
                    state.project().folders.get(1).uuid,
                    true),
                "");
        if (!world.stream()
            .map(r -> r.node().id())
            .toList()
            .equals(List.of("second", "clear", "everywhere", "first", "set", "everywhere")))
          throw new AssertionError("Wrong mixed World tree order");
        var p = state.project();
        var before = ProjectIO.JSON.valueToTree(p);
        var exported =
            ResourceGraph.selection(
                p, List.of(p.worldGenerator.layers.getFirst().operations.getFirst().uuid), true);
        if (!before.equals(ProjectIO.JSON.valueToTree(p)) || exported.folders.size() != 1)
          throw new AssertionError("Export lost ancestry or mutated source");
        new ProjectIO().save(output.resolve("organized-project.json"), p);
        double field = new CompiledProject(p).noise3D("constant", 3).sample(-5, 7, 8);
        if (field != 1) throw new AssertionError("Noise changed");
        try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
            var worldContext =
                engine.openWorld(
                    new CompiledProject(p),
                    new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!worldContext.generateChunk(new ChunkPos(0, 0, 0)).isEmpty())
            throw new AssertionError("SET then CLEAR is not EMPTY");
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("execution", p.worldGenerator.layers.stream().map(l -> l.id).toList());
        report.put("worldRows", world.stream().map(r -> r.node().id()).toList());
        report.put("worldDepths", world.stream().map(ResourceTreeIndex.Row::depth).toList());
        report.put(
            "noiseRoot",
            browser.project("noise3D", Map.of(), "").stream().map(r -> r.node().id()).toList());
        report.put(
            "stages", p.worldGenerator.layers.stream().map(l -> browser.stage(l.uuid)).toList());
        report.put("submittedRows", panel.browserStats().treeRows());
        report.put("field", field);
        Files.writeString(
            output.resolve("hierarchy-reference.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "HIERARCHY_REFERENCE_NATIVE_PASS mixed root order / world folders / visible stages /"
                + " one Undo-Redo / immutable export / unchanged computation identity / SET-CLEAR"
                + " expectations");
      }
    } finally {
      ImGui.destroyContext();
    }
  }
}
