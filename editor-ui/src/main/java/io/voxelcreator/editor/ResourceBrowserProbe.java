package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiCond;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Isolated native list-clipper and scroll oracle with explicitly created large resources. */
public final class ResourceBrowserProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Pass the isolated probe output directory");
    Path output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 900);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        var fixture = fixture();
        if (!state.change(
            p -> {
              p.folders.addAll(fixture.folders);
              p.noise2D.addAll(fixture.noise2D);
              p.voxelTypes.addAll(fixture.voxelTypes);
              p.voxelSets.addAll(fixture.voxelSets);
              p.voxelGroups.addAll(fixture.voxelGroups);
            })) throw new AssertionError(state.issues);
        var panel = new ResourcePanel(state, new PropertyPanel(state));
        panel.showManager();
        String project = ProjectIO.JSON.writeValueAsString(state.project());
        Object draft = state.draft;
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        draw(panel, 4);
        var top = panel.browserStats();
        if (top.indexed() != 7102
            || top.treeRows() > 50
            || top.managerRows() > 50
            || top.treeRows() < 2
            || top.managerRows() < 2)
          throw new AssertionError("Unbounded or empty top rendering: " + top);
        panel.probeScroll(18000);
        draw(panel, 4);
        var scrolled = panel.browserStats();
        if (scrolled.treeRows() > 50
            || scrolled.managerRows() > 50
            || scrolled.submitted().equals(top.submitted()))
          throw new AssertionError(
              "Scroll did not produce a bounded different viewport: " + scrolled);
        if (top.builds() != scrolled.builds() || top.projections() != scrolled.projections())
          throw new AssertionError("Scrolling rebuilt browser data");
        panel.probeFilter("noise-01999", "type-04999");
        panel.probeScroll(0);
        draw(panel, 4);
        var filtered = panel.browserStats();
        UUID target = fixture.noise2D.getLast().uuid;
        if (!filtered.submitted().contains(target)
            || filtered.treeRows() != 3
            || filtered.managerRows() != 1)
          throw new AssertionError("Ancestor/filter membership was lost: " + filtered);
        if (filtered.builds() != 1) throw new AssertionError("Filtering rebuilt project metadata");
        var sample = new NoiseCompiler(state.project(), 7).compile(target);
        if (sample.sample(2, 0, -3) != 199.9
            || !project.equals(ProjectIO.JSON.writeValueAsString(state.project()))
            || draft != state.draft
            || revision != state.revision
            || history != state.document.historyBytes()
            || dirty != state.document.dirty())
          throw new AssertionError("Browsing changed project state/evaluation");
        var index = new ResourceTreeIndex(state.project());
        var rows = index.project("noise2D", Map.of(), "noise-01999");
        var filteredLabels = rows.stream().map(r -> r.node().id()).toList();
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("noiseCount", 2000);
        report.put("voxelCount", 5000);
        report.put("indexCount", index.size());
        report.put("top", top);
        report.put("scrolled", scrolled);
        report.put("filtered", filtered);
        report.put("filteredLabels", filteredLabels);
        report.put("filteredDepths", rows.stream().map(ResourceTreeIndex.Row::depth).toList());
        report.put(
            "filteredTypeIds",
            index.kind("voxel", "type-04999").stream().map(ResourceTreeIndex.Node::id).toList());
        report.put("unchangedSample", sample.sample(-4, 0, 8));
        Files.writeString(
            output.resolve("resource-browser.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "RESOURCE_BROWSER_REFERENCE_NATIVE_PASS 7102 indexed / bounded viewport widgets /"
                + " native scroll / cached projections / filtered ancestors / voxel filter /"
                + " document, draft, history and numeric invariance");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void draw(ResourcePanel panel, int count) {
    for (int i = 0; i < count; i++) {
      ImGui.newFrame();
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(430, 750, ImGuiCond.Always);
      panel.draw();
      ImGui.render();
    }
  }

  private static ProjectData fixture() {
    var p = new ProjectData();
    var outer = new Folder();
    outer.id = "outer";
    outer.rootKind = "noise2D";
    var inner = new Folder();
    inner.id = "inner";
    inner.rootKind = "noise2D";
    inner.parentFolderUuid = outer.uuid;
    p.folders.addAll(List.of(outer, inner));
    for (int i = 0; i < 2000; i++) {
      var n = new Noise();
      n.id = String.format(Locale.ROOT, "noise-%05d", i);
      n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear();
      n.algorithm.parameters.put("value", i / 10.0);
      if (i == 1999) n.parentFolderUuid = inner.uuid;
      p.noise2D.add(n);
    }
    for (int i = 0; i < 5000; i++) {
      var t = new VoxelType();
      t.id = String.format(Locale.ROOT, "type-%05d", i);
      p.voxelTypes.add(t);
    }
    for (int i = 0; i < 50; i++) {
      var set = new Membership();
      set.id = "set-" + i;
      p.voxelSets.add(set);
      var group = new Membership();
      group.id = "group-" + i;
      p.voxelGroups.add(group);
    }
    return p;
  }
}
