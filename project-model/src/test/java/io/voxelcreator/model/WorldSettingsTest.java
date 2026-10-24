package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldSettingsTest {
  @Test
  void fieldMappingsHaveExplicitEndpointsAndStableExtremeSpans() {
    var d = new FieldDriver();
    d.inputMin = -2;
    d.inputMax = 2;
    d.outputMin = .2;
    d.outputMax = .8;
    assertEquals(.2, WorldSemantics.map(d, -100));
    assertEquals(.8, WorldSemantics.map(d, 100), 1e-15);
    assertEquals(.35, WorldSemantics.map(d, -1), 1e-15);
    d.mapping = "SMOOTHSTEP";
    assertEquals(.29375, WorldSemantics.map(d, -1), 1e-15);
    d.outputMin = 1;
    d.outputMax = 0;
    assertEquals(.84375, WorldSemantics.map(d, -1), 1e-15);
    d.mapping = "LINEAR";
    d.inputMin = -Double.MAX_VALUE;
    d.inputMax = Double.MAX_VALUE;
    assertEquals(.5, WorldSemantics.map(d, 0));
    assertEquals(.25, WorldSemantics.map(d, Double.MAX_VALUE / 2), 1e-15);
    assertThrows(IllegalArgumentException.class, () -> WorldSemantics.map(d, Double.NaN));
  }

  @Test
  void fixedRegistryAndAtomicOverridesPreserveLiveInheritance() {
    var names = new LinkedHashSet<String>();
    for (var f : Settings.class.getFields()) names.add(f.getName());
    assertEquals(names, SettingsResolver.PROPERTIES);
    var layer = new Layer();
    var op = new Operation();
    layer.defaults.axisX = "Z";
    var transform = new SamplingTransform();
    transform.x = 3;
    layer.defaults.samplingTransforms.add(transform);
    layer.defaults.probabilityDriver.enabled = true;
    op.overrides.put("axisX", ProjectIO.JSON.valueToTree("-X"));
    op.overrides.put("scaleX", ProjectIO.JSON.valueToTree(0));
    op.overrides.put("samplingTransforms", ProjectIO.JSON.valueToTree(List.of()));
    op.overrides.put("probabilityDriver", ProjectIO.JSON.valueToTree(new FieldDriver()));
    var resolved = SettingsResolver.resolve(layer, op);
    assertEquals("-X", resolved.axisX);
    assertEquals(0, resolved.scaleX);
    assertTrue(resolved.samplingTransforms.isEmpty());
    assertFalse(resolved.probabilityDriver.enabled);
    layer.defaults.axisX = "Y";
    transform.x = 8;
    assertEquals("-X", SettingsResolver.resolve(layer, op).axisX);
    op.overrides.remove("axisX");
    op.overrides.remove("samplingTransforms");
    resolved = SettingsResolver.resolve(layer, op);
    assertEquals("Y", resolved.axisX);
    assertEquals(8, resolved.samplingTransforms.getFirst().x);
    resolved.samplingTransforms.getFirst().x = 900;
    assertEquals(8, transform.x);
  }

  @Test
  void intervalLookupHonorsSingletonsAndEitherSharedEndpoint() {
    var a = new TypeInterval();
    a.min = -1;
    a.max = 0;
    var b = new TypeInterval();
    b.min = 0;
    b.max = 0;
    b.includeMax = true;
    var c = new TypeInterval();
    c.min = 0;
    c.max = 1;
    c.includeMin = false;
    c.includeMax = true;
    var ranges = List.of(c, b, a);
    assertEquals(2, WorldSemantics.interval(ranges, -1));
    assertEquals(1, WorldSemantics.interval(ranges, 0));
    assertEquals(0, WorldSemantics.interval(ranges, 1));
    assertEquals(-1, WorldSemantics.interval(ranges, 2));
  }
}
