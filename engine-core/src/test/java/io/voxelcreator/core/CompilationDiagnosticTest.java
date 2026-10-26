package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompilationDiagnosticTest {
  @Test
  void invalidBindingsNameTheInputAndEscapeItsPointerToken() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "n";
    p.noise2D.add(n);
    var item = ProcessingRegistry.create("EXPRESSION", "formula");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    item.inputs.put("a/b~c", Binding.constant(0));
    item.inputs.get("a/b~c").value = null;
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    var failure =
        assertThrows(CompilationFailure.class, () -> new NoiseCompiler(p, 0).compile(n.uuid));
    assertEquals("inputs/a/b~c", failure.diagnostic().parameterKey());
    assertTrue(failure.diagnostic().jsonPointer().endsWith("/inputs/a~1b~0c"));
    assertEquals(item.uuid, failure.diagnostic().itemUuid());
    assertEquals(n.uuid, failure.diagnostic().sourceUuid());
  }

  @Test
  void incompatibleExpressionInputAndMissingOrdinaryInputHaveBindingTargets() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "n";
    p.noise2D.add(n);
    var item = ProcessingRegistry.create("EXPRESSION", "formula");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    var vector = Binding.constant(0);
    vector.type = "Vector2";
    vector.value = ProjectIO.JSON.valueToTree(new double[] {1, 2});
    item.inputs.put("vector", vector);
    n.processing.add(item);
    assertEquals("inputs/vector", NoiseCompiler.validate(p).getFirst().parameterKey());
    item.kind = "ADD";
    item.inputs.clear();
    assertEquals("inputs/a", NoiseCompiler.validate(p).getFirst().parameterKey());
  }

  @Test
  void parserFailureRetainsOwningItemParameterAndOriginalSpanCause() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "n";
    p.noise2D.add(n);
    var item = ProcessingRegistry.create("EXPRESSION", "bad-expression");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    var issue = NoiseCompiler.validate(p).getFirst();
    assertEquals(n.uuid, issue.sourceUuid());
    assertEquals(item.uuid, issue.itemUuid());
    assertEquals("expression", issue.parameterKey());
    var failure =
        assertThrows(CompilationFailure.class, () -> new NoiseCompiler(p, 0).compile(n.uuid));
    assertInstanceOf(Expression.Problem.class, failure.getCause());
    assertEquals(issue, failure.diagnostic());
  }

  @Test
  void referencedFailureKeepsTheReferencedResourceInsteadOfTheCaller() {
    var p = new ProjectData();
    var a = new Noise();
    a.id = "a";
    var b = new Noise();
    b.id = "b";
    p.noise2D.addAll(List.of(a, b));
    var bad = ProcessingRegistry.create("EXPRESSION", "bad");
    bad.parameters.put("expression", ProjectIO.JSON.valueToTree("sqrt("));
    b.processing.add(bad);
    b.outputBinding = Binding.earlier(bad.uuid);
    var caller = ProcessingRegistry.create("ADD", "caller");
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = b.uuid;
    caller.inputs.put("a", ref);
    a.processing.add(caller);
    a.outputBinding = Binding.earlier(caller.uuid);
    var failure =
        assertThrows(CompilationFailure.class, () -> new NoiseCompiler(p, 0).compile(a.uuid));
    assertEquals(b.uuid, failure.diagnostic().sourceUuid());
    assertEquals(bad.uuid, failure.diagnostic().itemUuid());
  }

  @Test
  void transformCompilerFailureNamesTheTransform() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "n";
    p.noise2D.add(n);
    var transform = new Item();
    transform.id = "bad";
    transform.kind = "ROTATE";
    transform.resultType = "Coordinates2D";
    transform.parameters.put("rotationOrder", ProjectIO.JSON.valueToTree("XXZ"));
    n.coordinateTransforms.add(transform);
    var failure =
        assertThrows(CompilationFailure.class, () -> new NoiseCompiler(p, 0).compile(n.uuid));
    assertEquals(n.uuid, failure.diagnostic().sourceUuid());
    assertEquals(transform.uuid, failure.diagnostic().itemUuid());
  }
}
