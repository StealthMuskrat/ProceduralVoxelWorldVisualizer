package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompilerOptimizationTest {
  private static ProjectData project() {
    var p = new ProjectData();
    p.project.minY = -3;
    p.project.maxY = 6;
    var n = new Noise();
    n.id = "optimized";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 2.0);
    p.noise2D.add(n);
    return p;
  }

  private static Item expression(Noise n, String text) {
    var item = ProcessingRegistry.create("EXPRESSION", "expression-" + n.processing.size());
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    return item;
  }

  @Test
  void projectAndSharedConstantsFoldWithoutSamplingAnUnusedSource() {
    var p = project();
    var n = p.noise2D.getFirst();
    n.algorithm.key = "SPHERE_SDF";
    n.algorithm.parameters.clear();
    var shared = new Parameter();
    shared.id = "bias";
    shared.value = ProjectIO.JSON.valueToTree(3);
    n.sharedParameters.add(shared);
    var item = expression(n, "true ? bias*2+worldHeight : source");
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = shared.uuid;
    item.inputs.put("bias", binding);
    var compiler = new NoiseCompiler(p, 11);
    var field = compiler.compile(n.uuid);
    assertEquals(16, field.sample(Double.MAX_VALUE, 0, Double.MAX_VALUE));
    assertEquals(1, compiler.statistics(n.uuid).constantItems());
    assertEquals(0, compiler.statistics(n.uuid).sampleWork());
    shared.value = ProjectIO.JSON.valueToTree(100);
    assertEquals(16, field.sample(-1, 0, 2));
  }

  @Test
  void compatiblePureItemsShareSuccessfulResults() {
    var p = project();
    var n = p.noise2D.getFirst();
    var a = ProcessingRegistry.create("ADD", "a");
    var b = ProcessingRegistry.create("ADD", "b");
    n.processing.addAll(List.of(a, b));
    var sum = ProcessingRegistry.create("ADD", "sum");
    sum.inputs.put("a", Binding.earlier(a.uuid));
    sum.inputs.put("b", Binding.earlier(b.uuid));
    n.processing.add(sum);
    n.outputBinding = Binding.earlier(sum.uuid);
    var compiler = new NoiseCompiler(p, 0);
    var field = compiler.compile(n.uuid);
    assertEquals(1, compiler.statistics(n.uuid).sharedItems());
    assertEquals(6, field.sample(1, 0, 2));
    assertEquals(6, field.sample(-100, 0, 7));
  }

  @Test
  void sharedFailureKeepsTheSelectedItemsIdentity() {
    var p = project();
    var n = p.noise2D.getFirst();
    n.algorithm.parameters.put("value", 0.0);
    var a = ProcessingRegistry.create("DIVIDE", "first");
    var b = ProcessingRegistry.create("DIVIDE", "second");
    for (var item : List.of(a, b)) {
      item.inputs.put("a", Binding.constant(1));
      item.inputs.put("b", Binding.source());
      n.processing.add(item);
    }
    n.outputBinding = Binding.earlier(b.uuid);
    var compiler = new NoiseCompiler(p, 0);
    var field = compiler.compile(n.uuid);
    assertEquals(1, compiler.statistics(n.uuid).sharedItems());
    var failure = assertThrows(EvaluationFailure.class, () -> field.sample(1, 0, 2));
    assertEquals(b.uuid, failure.itemUuid);
  }

  @Test
  void unusedExpressionBindingsDoNotContributeRuntimeWork() {
    var p = project();
    var n = p.noise2D.getFirst();
    var other = new Noise();
    other.id = "reference";
    other.algorithm.key = "SPHERE_SDF";
    p.noise2D.add(other);
    var item = expression(n, "true ? 7 : source+unused");
    var binding = Binding.constant(0);
    binding.sourceKind = "Noise Generator Reference";
    binding.noiseGeneratorUuid = other.uuid;
    item.inputs.put("unused", binding);
    var compiler = new NoiseCompiler(p, 0);
    var field = compiler.compile(n.uuid);
    assertEquals(7, field.sample(Double.MAX_VALUE, 0, Double.MAX_VALUE));
    assertEquals(0, compiler.statistics(n.uuid).sampleWork());
    item.inputs.get("unused").type = "Boolean";
    assertFalse(NoiseCompiler.validate(p).isEmpty());
  }

  @Test
  void sampledPrefixesAndFullFieldsKeepIndependentExpressionScratch() {
    var p = project();
    var n = p.noise2D.getFirst();
    var squared = expression(n, "x*x+z*z");
    var offset = ProcessingRegistry.create("OFFSET_SAMPLE", "offset");
    offset.inputs.put("field", Binding.earlierField(squared.uuid));
    offset.parameters.put("x", ProjectIO.JSON.valueToTree(1));
    offset.parameters.put("z", ProjectIO.JSON.valueToTree(2));
    n.processing.add(offset);
    var difference = ProcessingRegistry.create("SUBTRACT", "difference");
    difference.inputs.put("a", Binding.earlier(offset.uuid));
    difference.inputs.put("b", Binding.earlier(squared.uuid));
    n.processing.add(difference);
    n.outputBinding = Binding.earlier(difference.uuid);
    var compiler = new NoiseCompiler(p, 0);
    var field = compiler.compile(n.uuid);
    for (int x = -33; x < 34; x++)
      for (int z = -4; z <= 4; z++) assertEquals(2 * x + 4 * z + 5, field.sample(x, 0, z));
  }

  @Test
  void implicitSourceWorkAndUnselectedInvalidConstantsRemainVisible() {
    var p = project();
    var n = p.noise2D.getFirst();
    var source = expression(n, "source+source");
    var compiler = new NoiseCompiler(p, 0);
    assertEquals(4, compiler.compile(n.uuid).sample(0, 0, 0));
    assertTrue(compiler.statistics(n.uuid).sampleWork() >= 16);
    var invalid = expression(n, "1/0");
    n.outputBinding = Binding.earlier(source.uuid);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    assertEquals(4, new NoiseCompiler(p, 0).compile(n.uuid).sample(0, 0, 0));
    n.outputBinding = Binding.earlier(invalid.uuid);
    var failure =
        assertThrows(
            EvaluationFailure.class, () -> new NoiseCompiler(p, 0).compile(n.uuid).sample(0, 0, 0));
    assertEquals(invalid.uuid, failure.itemUuid);
  }
}
