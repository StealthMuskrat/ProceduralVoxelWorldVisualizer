package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreationDefaultsTest {
  @Test
  void allBuiltInCreationControlsValidateAndAreDefensivelyOwned() {
    var metadata = AppMetadata.defaults();
    metadata.validate();
    assertTrue(CreationRegistry.CONTROLS.size() > 150);
    for (var spec : CreationRegistry.CONTROLS.values()) {
      spec.validate(spec.builtIn());
      if (spec.builtIn().isArray()) {
        var copy = (com.fasterxml.jackson.databind.node.ArrayNode) spec.builtIn();
        copy.removeAll();
        assertFalse(spec.builtIn().isEmpty());
      }
    }
    assertThrows(UnsupportedOperationException.class, () -> CreationRegistry.CONTROLS.clear());
    var bare = new AppMetadata();
    bare.control("noise").label = "mutated";
    assertNotEquals("mutated", AppMetadata.defaults().control("noise").label);
    bare.orderedControls().getFirst().getValue().label = "changed";
    assertNotEquals(
        "changed", AppMetadata.defaults().orderedControls().getFirst().getValue().label);
  }

  @Test
  void everyAlgorithmAndDimensionPreservesCanonicalUnconfiguredNumerics() {
    for (int dimension : new int[] {2, 3})
      for (String key : NoiseAlgorithms.KEYS) {
        var metadata = AppMetadata.defaults();
        set(metadata, "noise" + dimension + "D.algorithm", key);
        var created = EditorCreations.noise(metadata, dimension, "field", null);
        var canonical = new Noise();
        canonical.uuid = created.uuid;
        canonical.id = "field";
        canonical.dimension = dimension;
        canonical.algorithm.key = key;
        var a = new ProjectData();
        var b = new ProjectData();
        (dimension == 2 ? a.noise2D : a.noise3D).add(created);
        (dimension == 2 ? b.noise2D : b.noise3D).add(canonical);
        var first = new NoiseCompiler(a, 17).compile(created.uuid);
        var second = new NoiseCompiler(b, 17).compile(canonical.uuid);
        for (double x : new double[] {-7.25, -0., 0, 2.125, 31})
          for (double z : new double[] {-11, .125, 3})
            assertEquals(
                Double.doubleToLongBits(second.sample(x, dimension == 2 ? 0 : 4.5, z)),
                Double.doubleToLongBits(first.sample(x, dimension == 2 ? 0 : 4.5, z)),
                key + " / " + dimension);
      }
  }

  @Test
  void allProcessingAndTransformFactoriesKeepRegistrySignaturesAndConcreteDefaults() {
    var metadata = AppMetadata.defaults();
    for (String key : ProcessingRegistry.ITEMS.keySet()) {
      var item = EditorCreations.processing(metadata, key, "created");
      var original = ProcessingRegistry.create(key, "original");
      assertEquals(original.kind, item.kind);
      assertEquals(original.resultType, item.resultType);
      assertEquals(original.inputs.keySet(), item.inputs.keySet());
      for (var port : original.inputs.entrySet()) {
        var created = item.inputs.get(port.getKey());
        assertEquals(port.getValue().sourceKind, created.sourceKind);
        assertEquals(port.getValue().type, created.type);
        if (port.getValue().sourceKind.equals("Constant"))
          assertEquals(port.getValue().value, created.value);
      }
    }
    for (int dimension : new int[] {2, 3})
      for (String key : CreationRegistry.TRANSFORMS) {
        var item = EditorCreations.transform(metadata, dimension, key, "transform");
        assertEquals("Coordinates" + dimension + "D", item.resultType);
        assertFalse(item.parameters.isEmpty());
      }
    assertThrows(
        IllegalArgumentException.class,
        () -> EditorCreations.transform(metadata, 4, "SCALE", "bad"));
  }

  @Test
  void configuredFactoriesHaveIndependentMathematicalExpectationsAndFutureOnlyOwnership() {
    var metadata = CreationProfileProbe.profile();
    var project = CreationProfileProbe.created(metadata);
    var compiled = new CompiledProject(project);
    for (int x = -5; x <= 5; x++) {
      assertEquals(2.5, compiled.noise2D("created-multiply", 91).sample(x, 7));
      assertEquals(1, compiled.noise3D("created-add", 91).sample(x, -8, 7));
      assertEquals(4.5, compiled.noise2D("created-curve", 91).sample(x, 7));
      assertEquals(10, compiled.noise2D("created-gradient", 91).sample(x, 7));
    }
    assertEquals(12, project.voxelTypes.getFirst().color.r);
    assertEquals(34, project.voxelTypes.getFirst().color.g);
    assertEquals(56, project.voxelTypes.getFirst().color.b);
    String before = ProjectIO.JSON.valueToTree(project).toString();
    set(metadata, "noise2D.algorithm.CONSTANT.value", .9);
    set(metadata, "processing.MULTIPLY.input.b", 20);
    set(metadata, "curve.startValue", 99);
    assertEquals(before, ProjectIO.JSON.valueToTree(project).toString());
    assertEquals(2.5, new CompiledProject(project).noise2D("created-multiply", 91).sample(3, 4));
    assertEquals(
        .9, EditorCreations.noise(metadata, 2, "future", null).algorithm.parameters.get("value"));
  }

  @Test
  void allCreationIdentitiesAreFreshAndLiteralArraysDoNotAliasProfilesOrSiblings() {
    var metadata = AppMetadata.defaults();
    set(metadata, "processing.DOT.input.a", new double[] {1, 2, 3});
    var a = EditorCreations.processing(metadata, "DOT", "a");
    var b = EditorCreations.processing(metadata, "DOT", "b");
    assertNotEquals(a.uuid, b.uuid);
    ((com.fasterxml.jackson.databind.node.ArrayNode) a.inputs.get("a").value)
        .set(0, ProjectIO.JSON.valueToTree(90));
    assertEquals(1, b.inputs.get("a").value.get(0).doubleValue());
    assertEquals(1, metadata.creationDefaults.get("processing.DOT.input.a").get(0).doubleValue());
    var first = EditorCreations.curve(metadata);
    var second = EditorCreations.curve(metadata);
    assertNotEquals(first.uuid, second.uuid);
    assertNotEquals(first.points.getFirst().uuid, second.points.getFirst().uuid);
    first.points.getFirst().y = 73;
    assertEquals(0, second.points.getFirst().y);
  }

  @Test
  void exactLongSeedBoundariesAndRegistryRangesRejectCoercion() {
    for (long seed : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0}) {
      var m = AppMetadata.defaults();
      set(m, "noise2D.seedOffset", seed);
      assertEquals(seed, EditorCreations.noise(m, 2, "seed", null).seedOffset);
    }
    var m = AppMetadata.defaults();
    set(m, "noise2D.seedOffset", 1.5);
    assertThrows(IllegalArgumentException.class, m::validate);
    set(m, "noise2D.seedOffset", 0);
    set(m, "voxel.color.r", 256);
    assertThrows(IllegalArgumentException.class, m::validate);
    set(m, "voxel.color.r", 200);
    set(m, "noise2D.algorithm.OPEN_SIMPLEX_2.octaves", 1.25);
    assertThrows(IllegalArgumentException.class, m::validate);
    set(m, "noise2D.algorithm.OPEN_SIMPLEX_2.octaves", 3);
    set(m, "noise2D.algorithm.OPEN_SIMPLEX_2.frequency", 0);
    assertThrows(IllegalArgumentException.class, m::validate);
  }

  @Test
  void typedDefaultsUnknownKeysChoicesAndNonfiniteValuesRejectBeforeCreation() {
    var m = AppMetadata.defaults();
    set(m, "processing.AND.input.a", 1);
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "processing.DOT.input.a", new double[] {1, 2});
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "noise2D.algorithm", "FAKE");
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "processing.ADD.resultType", "Boolean");
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "processing.MULTIPLY.input.b", Double.NaN);
    assertThrows(IllegalArgumentException.class, m::validate);
  }

  @Test
  void curveRemapClampAndExpressionCrossConstraintsAreValidated() {
    var m = AppMetadata.defaults();
    set(m, "curve.min", 1);
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "curve.displayMin", 2);
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "processing.REMAP.parameter.inputMin", 1);
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "processing.CLAMP.parameter.min", 2);
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    set(m, "processing.EXPRESSION.parameter.expression", "sin(");
    assertThrows(IllegalArgumentException.class, m::validate);
    set(m, "processing.EXPRESSION.parameter.expression", "source+x");
    assertDoesNotThrow(m::validate);
  }

  @Test
  void copiesAndImportsRetainConcreteDefinitionsInsteadOfApplyingCurrentProfile() {
    var m = CreationProfileProbe.profile();
    var project = CreationProfileProbe.created(m);
    var original = project.noise2D.getFirst();
    set(m, "processing.MULTIPLY.input.b", 99);
    var copy = ResourceTransactions.duplicate(original, Noise.class);
    assertEquals(4, copy.processing.getFirst().inputs.get("b").value.doubleValue());
    var destination = new ProjectData();
    ResourceTransactions.mergeCopies(destination, project);
    assertEquals(
        4,
        destination.noise2D.getFirst().processing.getFirst().inputs.get("b").value.doubleValue());
    assertEquals(
        99,
        EditorCreations.processing(m, "MULTIPLY", "future").inputs.get("b").value.doubleValue());
  }

  private static void set(AppMetadata m, String id, Object value) {
    m.creationDefaults.put(id, ProjectIO.JSON.valueToTree(value));
  }
}
