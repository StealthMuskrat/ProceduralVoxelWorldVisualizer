package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class CoordinateTransformTest {
  private static Noise noise(ProjectData p, int dimension) {
    var n = new Noise();
    n.id = "transforms";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinates");
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree(dimension == 2 ? "x+100*z" : "x+10*y+100*z"));
    n.processing.add(expression);
    n.outputBinding = Binding.earlier(expression.uuid);
    return n;
  }

  private static Item transform(Noise n, String kind) {
    var t = new Item();
    t.id = kind.toLowerCase() + n.coordinateTransforms.size();
    t.kind = kind;
    t.resultType = n.dimension == 2 ? "Coordinates2D" : "Coordinates3D";
    n.coordinateTransforms.add(t);
    return t;
  }

  private static void param(Item t, String key, Object value) {
    t.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  private static NoiseAlgorithms.Field field(ProjectData p, Noise n) {
    assertTrue(NoiseCompiler.validate(p).isEmpty(), NoiseCompiler.validate(p).toString());
    return new NoiseCompiler(p, 0).compile(n.uuid);
  }

  @Test
  void earlierCoordinateSnapshotsAreNotRecomputedAfterLaterTransforms() {
    var p = new ProjectData();
    var n = noise(p, 2);
    var a = transform(n, "TRANSLATE");
    param(a, "x", 1);
    param(a, "z", 2);
    var b = transform(n, "SCALE");
    param(b, "x", 2);
    param(b, "z", 2);
    var c = transform(n, "TRANSLATE");
    var coordinates = Binding.earlier(a.uuid);
    coordinates.type = "Coordinates2D";
    c.inputs.put("coordinates", coordinates);
    param(c, "x", 2);
    assertEquals(505, field(p, n).sample(2, 0, 3));
  }

  @Test
  void sharedAndAmbientInputsUseIncomingCoordinateStage() {
    var p = new ProjectData();
    var n = noise(p, 2);
    var parameter = new Parameter();
    parameter.id = "distance";
    parameter.type = "Scalar";
    parameter.value = ProjectIO.JSON.valueToTree(3);
    n.sharedParameters.add(parameter);
    var a = transform(n, "TRANSLATE");
    var x = new Binding();
    x.sourceKind = "Ambient Value";
    x.ambientKey = "x";
    a.inputs.put("x", x);
    var z = new Binding();
    z.sourceKind = "Shared Parameter";
    z.parameterUuid = parameter.uuid;
    a.inputs.put("z", z);
    var f = field(p, n);
    assertEquals(604, f.sample(2, 0, 3));
    parameter.value = ProjectIO.JSON.valueToTree(100);
    assertEquals(604, f.sample(2, 0, 3));
  }

  @Test
  void rotationsHavePivotsAndExplicitNoncommutingAxisOrder() {
    var p = new ProjectData();
    var n = noise(p, 3);
    var rotation = transform(n, "ROTATE");
    param(rotation, "angleX", 90);
    param(rotation, "angleY", 90);
    param(rotation, "rotationOrder", "XYZ");
    assertEquals(68, field(p, n).sample(1, 2, 3), 1e-12);
    param(rotation, "rotationOrder", "YXZ");
    assertEquals(187, field(p, n).sample(1, 2, 3), 1e-12);
    var p2 = new ProjectData();
    var n2 = noise(p2, 2);
    var r2 = transform(n2, "ROTATE");
    param(r2, "pivotX", 10);
    param(r2, "pivotZ", 5);
    param(r2, "angle", 90);
    assertEquals(610, field(p2, n2).sample(11, 0, 5), 1e-12);
    r2.kind = "SCALE";
    param(r2, "x", 2);
    param(r2, "z", 3);
    assertEquals(512, field(p2, n2).sample(11, 0, 5), 1e-12);
  }

  @Test
  void warpDisplacementsCanSampleReferencedFields() {
    var p = new ProjectData();
    var n = noise(p, 2);
    var displacement = new Noise();
    displacement.id = "displacement";
    displacement.algorithm.key = "CONSTANT";
    displacement.algorithm.parameters.put("value", 1.);
    p.noise2D.add(displacement);
    var warp = transform(n, "DOMAIN_WARP");
    param(warp, "amplitude", 3);
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = displacement.uuid;
    warp.inputs.put("warpX", ref);
    warp.inputs.put("warpZ", Binding.constant(2));
    assertEquals(905, field(p, n).sample(2, 0, 3));
  }

  @Test
  void stageRestrictionsAreCheckedBeforeSourceSampling() {
    var p = new ProjectData();
    var n = noise(p, 2);
    var a = transform(n, "TRANSLATE");
    a.inputs.put("x", Binding.source());
    assertTrue(NoiseCompiler.validate(p).stream().anyMatch(d -> d.code().equals("INVALID_STAGE")));
    a.inputs.put("x", Binding.constant(0));
    var processing = ProcessingRegistry.create("COORDINATES_TO_VECTOR", "illegal-stage");
    var binding = Binding.earlier(a.uuid);
    binding.type = "Coordinates2D";
    processing.resultType = "Vector2";
    processing.inputs.put("a", binding);
    n.processing.add(processing);
    assertFalse(NoiseCompiler.validate(p).isEmpty());
  }

  @Test
  void prefixesAllocateCoordinateStageScratchAndKeepTransforms() {
    var p = new ProjectData();
    var n = noise(p, 2);
    var translation = transform(n, "TRANSLATE");
    param(translation, "x", 3);
    var scale = transform(n, "SCALE");
    var location = Binding.earlier(translation.uuid);
    location.type = "Coordinates2D";
    scale.inputs.put("coordinates", location);
    param(scale, "x", 2);
    var prefix = n.processing.getFirst();
    var offset = ProcessingRegistry.create("OFFSET_SAMPLE", "offset");
    param(offset, "x", 1);
    offset.inputs.put("field", Binding.earlierField(prefix.uuid));
    n.processing.add(offset);
    n.outputBinding = Binding.earlier(offset.uuid);
    assertEquals(312, field(p, n).sample(2, 0, 3));
  }
}
