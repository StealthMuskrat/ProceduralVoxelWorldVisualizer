package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class TypedProcessingTest {
  static Binding earlier(Item item) {
    var b = Binding.earlier(item.uuid);
    b.type = item.resultType;
    return b;
  }

  static Item literal(String id, String type, Object value) {
    var item = ProcessingRegistry.create("CONSTANT", id);
    item.resultType = type;
    item.parameters.put("value", ProjectIO.JSON.valueToTree(value));
    return item;
  }

  static double compile(ProjectData p, Noise n) {
    var diagnostics = NoiseCompiler.validate(p);
    assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    return new NoiseCompiler(p, 0).compile(n.uuid).sample(10, 20, 30);
  }

  @Test
  void explicitProjectionExpansionCoordinateConversionAndBoolean() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "typed";
    p.noise2D.add(n);
    var v = literal("v", "Vector3", new double[] {2, 3, 5});
    n.processing.add(v);
    var project = ProcessingRegistry.create("PROJECT_VECTOR", "project");
    project.inputs.put("a", earlier(v));
    project.parameters.put("axes", ProjectIO.JSON.valueToTree("XZ"));
    n.processing.add(project);
    var expand = ProcessingRegistry.create("EXPAND_VECTOR", "expand");
    expand.inputs.put("a", earlier(project));
    expand.inputs.put("b", Binding.constant(7));
    expand.parameters.put("axes", ProjectIO.JSON.valueToTree("XY"));
    n.processing.add(expand);
    var coords = ProcessingRegistry.create("VECTOR_TO_COORDINATES", "coords");
    coords.inputs.put("a", earlier(expand));
    n.processing.add(coords);
    var coords2 = ProcessingRegistry.create("PROJECT_COORDINATES", "coords2");
    coords2.inputs.put("a", earlier(coords));
    coords2.parameters.put("axes", ProjectIO.JSON.valueToTree("YZ"));
    n.processing.add(coords2);
    var value2 = ProcessingRegistry.create("COORDINATES_TO_VECTOR", "value2");
    value2.resultType = "Vector2";
    value2.inputs.put("a", earlier(coords2));
    n.processing.add(value2);
    var dot = ProcessingRegistry.create("DOT", "dot");
    dot.inputs.put("a", earlier(value2));
    dot.inputs.put("b", earlier(value2));
    n.processing.add(dot);
    var bool = literal("bool", "Boolean", true);
    n.processing.add(bool);
    var numeric = ProcessingRegistry.create("BOOLEAN_TO_SCALAR", "numeric");
    numeric.inputs.put("a", earlier(bool));
    n.processing.add(numeric);
    var sum = ProcessingRegistry.create("ADD", "sum");
    sum.inputs.put("a", earlier(dot));
    sum.inputs.put("b", earlier(numeric));
    n.processing.add(sum);
    n.outputBinding = earlier(sum);
    assertEquals(75, compile(p, n));
  }

  @Test
  void coordinateValuesRequireExplicitConversion() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "coords";
    p.noise3D.add(n);
    n.dimension = 3;
    var c = ProcessingRegistry.create("COORDINATES3", "location");
    c.inputs.put("x", Binding.constant(3));
    c.inputs.put("y", Binding.constant(4));
    n.processing.add(c);
    var length = ProcessingRegistry.create("LENGTH", "distance");
    length.inputs.put("a", earlier(c));
    n.processing.add(length);
    n.outputBinding = earlier(length);
    assertFalse(NoiseCompiler.validate(p).isEmpty());
    var convert = ProcessingRegistry.create("COORDINATES_TO_VECTOR", "as-vector");
    convert.inputs.put("a", earlier(c));
    n.processing.add(1, convert);
    length.inputs.put("a", earlier(convert));
    assertEquals(5, compile(p, n));
  }

  @Test
  void vectorRotationAndZeroNormalization() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "rotate";
    p.noise2D.add(n);
    var v = literal("v", "Vector2", new double[] {1, 0});
    n.processing.add(v);
    var r = ProcessingRegistry.create("VECTOR_ROTATE", "rotation");
    r.resultType = "Vector2";
    r.inputs.put("a", earlier(v));
    r.parameters.put("angle", ProjectIO.JSON.valueToTree(90));
    n.processing.add(r);
    var component = ProcessingRegistry.create("COMPONENT", "z");
    component.inputs.put("a", earlier(r));
    component.parameters.put("component", ProjectIO.JSON.valueToTree(1));
    n.processing.add(component);
    n.outputBinding = earlier(component);
    assertEquals(1, compile(p, n), 1e-14);
  }

  @Test
  void malformedNumericParametersAreRejectedAndTerraceTransitionIsExplicit() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "terrace";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", .375);
    p.noise2D.add(n);
    var t = ProcessingRegistry.create("TERRACE", "terrace");
    t.parameters.put("levels", ProjectIO.JSON.valueToTree(2));
    n.processing.add(t);
    n.outputBinding = earlier(t);
    assertEquals(0, compile(p, n));
    t.parameters.put("transition", ProjectIO.JSON.valueToTree(1));
    assertEquals(.421875, compile(p, n));
    t.parameters.put("levels", ProjectIO.JSON.valueToTree("invalid"));
    assertFalse(NoiseCompiler.validate(p).isEmpty());
  }

  @Test
  void selectAndExpressionDoNotEvaluateUnselectedEarlierResults() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "lazy";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 1.);
    p.noise2D.add(n);
    var bad = ProcessingRegistry.create("DIVIDE", "bad");
    bad.inputs.put("b", Binding.constant(0));
    n.processing.add(bad);
    var select = ProcessingRegistry.create("SELECT", "select");
    select.inputs.put("true", earlier(bad));
    select.inputs.put("false", Binding.constant(7));
    n.processing.add(select);
    n.outputBinding = earlier(select);
    assertEquals(7, compile(p, n));
    var condition = select.inputs.get("condition");
    condition.value = ProjectIO.JSON.valueToTree(true);
    assertThrows(
        EvaluationFailure.class, () -> new NoiseCompiler(p, 0).compile(n.uuid).sample(0, 0, 0));
    condition.value = ProjectIO.JSON.valueToTree(false);
    assertEquals(7, compile(p, n));
    var expr = ProcessingRegistry.create("EXPRESSION", "expression");
    expr.inputs.put("bad", earlier(bad));
    expr.parameters.put("expression", ProjectIO.JSON.valueToTree("false ? bad : 9"));
    n.processing.add(expr);
    n.outputBinding = earlier(expr);
    assertEquals(9, compile(p, n));
  }

  @Test
  void equalityRequiresMatchingTypesAndComparesEveryComponent() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "equality";
    p.noise2D.add(n);
    var a = literal("a", "Vector2", new double[] {1, 2});
    var b = literal("b", "Vector2", new double[] {1, 3});
    n.processing.add(a);
    n.processing.add(b);
    var eq = ProcessingRegistry.create("EQUAL", "eq");
    eq.inputs.put("a", earlier(a));
    eq.inputs.put("b", earlier(b));
    n.processing.add(eq);
    var scalar = ProcessingRegistry.create("BOOLEAN_TO_SCALAR", "as-scalar");
    scalar.inputs.put("a", earlier(eq));
    n.processing.add(scalar);
    n.outputBinding = earlier(scalar);
    assertEquals(0, compile(p, n));
    b.parameters.put("value", ProjectIO.JSON.valueToTree(new double[] {1, 2}));
    assertEquals(1, compile(p, n));
  }
}
