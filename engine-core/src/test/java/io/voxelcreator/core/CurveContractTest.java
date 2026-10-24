package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class CurveContractTest {
  @Test
  void autoAlignedAndFreeHaveDistinctValidatedSemantics() {
    var c = new Curve();
    c.points.getFirst().segmentInterpolation = "BEZIER";
    assertEquals(.25, new ScalarCurve(c).sample(.25), 1e-14);
    c.points.forEach(p -> p.handleMode = "FREE");
    assertEquals(.15625, new ScalarCurve(c).sample(.25), 1e-14);
    c.points.getFirst().handleMode = "ALIGNED";
    c.points.getFirst().outTangent = 2;
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(c));
    c.points.getFirst().inTangent = 2;
    assertEquals(.4375, new ScalarCurve(c).sample(.25), 1e-14);
  }

  @Test
  void displayRangeDoesNotClampAndCompiledCoefficientsAreImmutable() {
    var c = new Curve();
    c.points.getFirst().y = -2;
    c.points.getLast().y = 4;
    c.displayMin = -10;
    c.displayMax = 10;
    var f = new ScalarCurve(c);
    assertEquals(-.5, f.sample(.25));
    c.points.getLast().y = 100;
    c.displayMin = 0;
    c.displayMax = 1;
    assertEquals(2.5, f.sample(.75));
    assertEquals(4, f.sample(10));
  }

  @Test
  void duplicateIdentitiesAndUnknownHandleModesAreRejected() {
    var c = new Curve();
    c.points.getLast().uuid = c.points.getFirst().uuid;
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(c));
    c.points.getLast().uuid = UUID.randomUUID();
    c.points.getFirst().handleMode = "UNSUPPORTED";
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(c));
    c.points.getFirst().handleMode = "AUTO";
    c.displayMax = c.displayMin;
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(c));
  }

  @Test
  void autoBezierIsMonotoneOnUnevenIntervalsAndMixedSegments() {
    var c = new Curve();
    c.points.add(1, new Point(.1, .8));
    c.points.add(2, new Point(.6, .9));
    c.points.forEach(p -> p.segmentInterpolation = "BEZIER");
    var f = new ScalarCurve(c);
    double previous = -1;
    for (int i = 0; i <= 1000; i++) {
      double y = f.sample(i / 1000.);
      assertTrue(y >= previous - 1e-12 && y >= 0 && y <= 1);
      previous = y;
    }
    assertEquals(.8, f.sample(.1));
  }

  @Test
  void curveIdentitiesParticipateInProjectValidationAndDuplication() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "curve";
    p.noise2D.add(n);
    var item = ProcessingRegistry.create("CURVE", "ramp");
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    var curve = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
    curve.uuid = n.uuid;
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    assertTrue(NoiseCompiler.validate(p).stream().anyMatch(d -> d.code().equals("DUPLICATE_UUID")));
  }
}
