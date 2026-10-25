package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Isolated native Find/draft-guard reference with real mouse and Escape input. */
public final class UnifiedFindProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the isolated output directory");
    Path output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1440, 900);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        if (!state.change(
            p -> {
              var outer = new Folder();
              outer.id = "continent";
              outer.rootKind = "noise2D";
              var inner = new Folder();
              inner.id = "mountains";
              inner.rootKind = "noise2D";
              inner.parentFolderUuid = outer.uuid;
              p.folders.addAll(List.of(outer, inner));
              for (int i = 0; i < 1000; i++) {
                var n = new Noise();
                n.id = "field-" + i;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", .25);
                p.noise2D.add(n);
              }
              var editing = p.noise2D.get(1);
              editing.id = "editing";
              var expression = ProcessingRegistry.create("EXPRESSION", "formula");
              expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
              editing.processing.add(expression);
              editing.outputBinding = Binding.earlier(expression.uuid);
              var destination = p.noise2D.getLast();
              destination.id = "needle";
              destination.parentFolderUuid = inner.uuid;
              var formula = ProcessingRegistry.create("EXPRESSION", "linked-formula");
              formula.inputs.clear();
              var ref = new Binding();
              ref.sourceKind = "Noise Generator Reference";
              ref.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
              formula.inputs.put("linked", ref);
              formula.parameters.put("expression", ProjectIO.JSON.valueToTree("linked + 0.5"));
              destination.processing.add(formula);
              destination.outputBinding = Binding.earlier(formula.uuid);
              var curve = ProcessingRegistry.create("CURVE", "response");
              curve.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
              destination.processing.add(curve);
            })) throw new AssertionError(state.issues);
        UUID editing = state.project().noise2D.get(1).uuid,
            destination = state.project().noise2D.getLast().uuid,
            source = state.project().noise2D.getFirst().uuid;
        state.select("noise2D", editing);
        ((Noise) state.draft)
            .processing
            .getFirst()
            .parameters
            .put("expression", ProjectIO.JSON.valueToTree("sin("));
        var before = ProjectIO.JSON.valueToTree(state.project());
        Object draft = state.draft;
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var properties = new PropertyPanel(state);
        var find = new ResourceFindPanel(state, properties);
        var open = new ImBoolean(true);
        for (int i = 0; i < 4; i++) frame(find, open);
        int topRows = find.submitted();
        var topKeys = find.visibleKeys();
        long builds = find.builds(), queries = find.queries();
        find.scroll(15000);
        for (int i = 0; i < 4; i++) frame(find, open);
        int scrollRows = find.submitted();
        if (topRows < 1
            || topRows > 40
            || scrollRows < 1
            || scrollRows > 40
            || topKeys.equals(find.visibleKeys())
            || find.builds() != builds
            || find.queries() != queries)
          throw new AssertionError("Find scroll was unbounded or rebuilt query data");
        var filter =
            new ResourceSearchIndex.Filter(
                "needle",
                "noise2D",
                "All",
                "noise2D",
                state.project().folders.getFirst().uuid,
                null,
                false,
                ResourceSearchIndex.Mode.ID);
        find.filter(filter);
        find.scroll(0);
        for (int i = 0; i < 4; i++) frame(find, open);
        var index = new ResourceSearchIndex(state.project());
        var hit = index.search(filter).getFirst();
        if (find.submitted() != 1)
          throw new AssertionError("Root/directory/ID filtering was not exact");
        var center = find.center(hit.key());
        io.addMousePosEvent(center[0], center[1]);
        frame(find, open);
        io.addMouseButtonEvent(0, true);
        frame(find, open);
        io.addMouseButtonEvent(0, false);
        frame(find, open);
        frame(find, open);
        if (!find.guardDrawn() || state.draft != draft || !state.selected.equals(editing))
          throw new AssertionError("Find lost the rejected draft before a decision");
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame(find, open);
        io.addKeyEvent(ImGuiKey.Escape, false);
        frame(find, open);
        if (state.draft != draft || !state.selected.equals(editing))
          throw new AssertionError("Native Cancel lost the draft");
        var navigation = new SearchNavigation(state);
        if (navigation.request(hit.target()) != SearchNavigation.Result.NEEDS_DECISION
            || navigation.decide(SearchNavigation.Decision.DISCARD)
                != SearchNavigation.Result.NAVIGATED)
          throw new AssertionError("Explicit discard failed");
        var refs =
            index.search(
                new ResourceSearchIndex.Filter(
                    "",
                    "input binding",
                    "All",
                    "noise2D",
                    state.project().folders.getFirst().uuid,
                    source,
                    false,
                    ResourceSearchIndex.Mode.ANY_TEXT));
        if (refs.size() != 1
            || !refs.getFirst().target().pointer().endsWith("/processing/0/inputs/linked"))
          throw new AssertionError("Reference was not located at its exact binding");
        var points =
            index.search(
                new ResourceSearchIndex.Filter(
                    "",
                    "curve point",
                    "All",
                    "noise2D",
                    state.project().folders.getFirst().uuid,
                    null,
                    false,
                    ResourceSearchIndex.Mode.ANY_TEXT));
        var point = points.getLast();
        if (!properties.revealSearch(point.target())
            || !properties.searchCurveSelection().equals(Set.of(point.uuid())))
          throw new AssertionError("Curve point navigation selected the wrong point");
        properties.closeSearchCurve();
        double sample =
            new NoiseCompiler(state.project(), 17).compile(destination).sample(5, 0, -7);
        if (sample != .75
            || !before.equals(ProjectIO.JSON.valueToTree(state.project()))
            || revision != state.revision
            || history != state.document.historyBytes()
            || dirty != state.document.dirty())
          throw new AssertionError("Find changed the document or numerical results");
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("resources", 1000);
        report.put("topRows", topRows);
        report.put("scrollRows", scrollRows);
        report.put("builds", find.builds());
        report.put("queriesBeforeScroll", queries);
        report.put("queriesAfterScroll", queries);
        report.put("filteredRows", 1);
        report.put("breadcrumb", hit.breadcrumb());
        report.put("referenceCount", refs.size());
        report.put("referencePointer", refs.getFirst().target().pointer());
        report.put("curvePoints", points.size());
        report.put("selectedPoint", point.uuid());
        report.put("sample", sample);
        Files.writeString(
            output.resolve("unified-find.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "UNIFIED_FIND_NATIVE_PASS bounded results / cached scroll / root-directory-ID filters /"
                + " actual mouse activation / protected rejected draft / Escape Cancel / explicit"
                + " discard / precise binding / curve-point selection / numeric invariance");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void frame(ResourceFindPanel panel, ImBoolean open) {
    ImGui.newFrame();
    panel.draw(open);
    ImGui.render();
  }
}
