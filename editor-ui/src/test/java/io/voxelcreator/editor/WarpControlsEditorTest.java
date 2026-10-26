package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WarpControlsEditorTest {
  @Test
  void canonicalWarpControlSignaturesHavePresentationAndNumericEnums() {
    var m = AppMetadata.defaults();
    for (int d : new int[] {2, 3})
      for (var c : WarpRegistry.controls(d)) {
        String id = "transform" + d + "D.DOMAIN_WARP." + c.key();
        var spec = CreationRegistry.get(id);
        spec.validate(spec.builtIn());
        assertEquals(c.label(), m.controls.get(id).label);
        assertEquals(c.options(), spec.options());
        if (c.numericOptions()) assertEquals(CreationRegistry.Kind.INTEGER, spec.kind());
      }
    assertEquals(
        CreationRegistry.Kind.INTEGER,
        CreationRegistry.get("processing.WARP_SAMPLE.parameter.seedOffset").kind());
  }

  @Test
  void factoriesBakeOnlyFutureValuesAndProjectAxisOrderForNew2DItems() {
    var m = AppMetadata.defaults();
    var old = EditorCreations.processing(m, "WARP_SAMPLE", "old", 2);
    m.creationDefaults.put(
        "processing.WARP_SAMPLE.parameter.amplitude", ProjectIO.JSON.valueToTree(2));
    m.creationDefaults.put(
        "processing.WARP_SAMPLE.parameter.warpOrder", ProjectIO.JSON.valueToTree("ZYX"));
    var created = EditorCreations.processing(m, "WARP_SAMPLE", "created", 2);
    assertEquals(8, old.parameters.get("amplitude").doubleValue());
    assertEquals(2, created.parameters.get("amplitude").doubleValue());
    assertEquals("ZX", created.parameters.get("warpOrder").textValue());
    assertNotEquals(old.uuid, created.uuid);
  }

  @Test
  void invalidProfileSourceAndOctaveProductsRejectBeforePublication() {
    var m = AppMetadata.defaults();
    m.creationDefaults.put(
        "processing.WARP_SAMPLE.parameter.source.fractal", ProjectIO.JSON.valueToTree(9));
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    m.creationDefaults.put(
        "transform2D.DOMAIN_WARP.source.extentX", ProjectIO.JSON.valueToTree(-1));
    assertThrows(IllegalArgumentException.class, m::validate);
  }

  @Test
  void supportedSourceControlsAndModeVisibilityAreDimensionAware() {
    var i = ProcessingRegistry.create("WARP_SAMPLE", "warp", 2);
    i.parameters.put("warpAlgorithm", ProjectIO.JSON.valueToTree("CONSTANT"));
    var keys = WarpRegistry.visibleControls(i, 2).stream().map(WarpRegistry.Control::key).toList();
    assertTrue(keys.contains("source.value"));
    assertFalse(keys.contains("source.fractal"));
    assertFalse(keys.contains("warpOrder"));
    assertFalse(keys.contains("warpGain"));
    i.parameters.put("warpMode", ProjectIO.JSON.valueToTree("SEQUENTIAL"));
    i.parameters.put("warpOctaves", ProjectIO.JSON.valueToTree(2));
    keys = WarpRegistry.visibleControls(i, 2).stream().map(WarpRegistry.Control::key).toList();
    assertTrue(keys.containsAll(List.of("warpOrder", "warpGain", "warpLacunarity", "octaveSeeds")));
  }

  @Test
  void fieldDependenciesParticipateInGuardedStructuralEdits() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "dependencies";
    n.algorithm.key = "CONSTANT";
    p.noise2D.add(n);
    var prefix = ProcessingRegistry.create("EXPRESSION", "prefix");
    n.processing.add(prefix);
    var w = ProcessingRegistry.create("WARP_SAMPLE", "warp");
    w.inputs.put("displacementX", Binding.earlierField(prefix.uuid));
    n.processing.add(w);
    n.outputBinding = Binding.earlier(w.uuid);
    var plan =
        NoiseAuthoring.plan(
            p, n, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.DELETE, prefix.uuid));
    assertFalse(plan.valid());
    var resolved =
        NoiseAuthoring.plan(
            p,
            n,
            new NoiseAuthoring.Request(
                NoiseAuthoring.Action.DELETE, prefix.uuid, NoiseAuthoring.Resolution.SOURCE, null));
    assertTrue(resolved.valid(), resolved.diagnostics().toString());
    resolved.applyTo(n);
    assertEquals("Source Field", n.processing.getFirst().inputs.get("displacementX").sourceKind);
  }

  @Test
  void privateWarpApplyIsOneUndoAndRejectedControlPreservesDraft() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var fixture =
          ProjectIO.JSON.readValue(
              Path.of("../fixtures/warp-fields.json").toFile(), ProjectData.class);
      assertTrue(
          state.change(
              p -> {
                p.noise2D.addAll(fixture.noise2D);
                p.noise3D.addAll(fixture.noise3D);
              }));
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      var item = draft.processing.getLast();
      item.parameters.put("amplitude", ProjectIO.JSON.valueToTree(2));
      item.parameters.put("warpMode", ProjectIO.JSON.valueToTree("SEQUENTIAL"));
      assertTrue(state.applyDraft());
      assertEquals(198, new NoiseCompiler(state.project(), 17).compile(n.uuid).sample(2, 0, 3));
      state.document.undo();
      assertEquals(55, new NoiseCompiler(state.project(), 17).compile(n.uuid).sample(2, 0, 3));
      state.document.redo();
      state.refreshDraft();
      item = ((Noise) state.draft).processing.getLast();
      item.parameters.put("warpOctaves", ProjectIO.JSON.valueToTree(0));
      var before = ProjectIO.JSON.valueToTree(state.draft);
      assertFalse(state.applyDraft());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      assertTrue(state.issues.stream().anyMatch(d -> "warpOctaves".equals(d.parameterKey())));
    }
  }

  @Test
  void crossResourceDisplacementDependenciesHaveOwnedClosure() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/warp-fields.json").toFile(), ProjectData.class);
    var source = p.noise2D.getFirst();
    var exported = ResourceGraph.selection(p, Set.of(source.uuid), true);
    assertEquals(3, exported.noise2D.size());
    assertTrue(exported.noise2D.stream().anyMatch(n -> n.id.equals("displacement-x")));
    assertFalse(exported.noise2D.stream().anyMatch(n -> n.id.equals("domain-warp")));
  }
}
