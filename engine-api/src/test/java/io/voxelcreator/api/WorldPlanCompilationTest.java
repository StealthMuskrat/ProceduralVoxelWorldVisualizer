package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred construction-cost boundary; no injected compiler or new public observation API. */
class WorldPlanCompilationTest {
  @JsonSerialize(using = CountingSerializer.class)
  public static final class CountingNoises extends ArrayList<Noise> {
    int copies;
  }

  public static final class CountingSerializer extends StdSerializer<CountingNoises> {
    public CountingSerializer() { super(CountingNoises.class); }
    @Override public void serialize(CountingNoises value, JsonGenerator out, SerializerProvider provider)
        throws IOException {
      value.copies++;
      provider.defaultSerializeValue(new ArrayList<>(value), out);
    }
  }

  private record Fixture(ProjectData source, Layer layer, CountingNoises noises) {}

  private Fixture fixture(int placements) {
    var source = new ProjectData();
    var noise = new Noise(); noise.id = "field"; noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear(); noise.algorithm.parameters.put("value", .25);
    var noises = new CountingNoises(); noises.add(noise); source.noise2D = noises;
    var layer = new Layer(); layer.id = "layer"; layer.defaults.seedOffset = 37;
    source.worldGenerator.layers.add(layer);
    for (int i = 0; i < placements; i++) {
      var op = new Operation(); op.id = "placement-" + i; op.noiseGeneratorUuid = noise.uuid;
      op.interpretation = "ALL";
      if (i % 3 == 0) op.overrides.put("seedOffset", ProjectIO.JSON.valueToTree(0));
      if (i % 3 == 1) op.overrides.put("seedOffset", ProjectIO.JSON.valueToTree(37));
      op.overrides.put("offsetX", ProjectIO.JSON.valueToTree(i));
      layer.operations.add(op);
    }
    return new Fixture(source, layer, noises);
  }

  @Test
  void hundredsOfPlacementsCopyCanonicalDefinitionsOncePerEffectiveSeed() {
    var f = fixture(240);
    assertTrue(Validator.validate(f.source).isEmpty());
    var before = ProjectIO.JSON.valueToTree(f.source); f.noises.copies = 0;
    var plan = new WorldPlan(f.source, new VoxelRegistry(f.source.voxelTypes), 17);
    assertEquals(2, f.noises.copies);
    assertEquals(241, plan.stages().size());
    assertEquals(0, plan.sample(-3, 1, 7));
    assertEquals(2, f.noises.copies, "Runtime sampling must not recopy canonical definitions");
    assertEquals(before, ProjectIO.JSON.valueToTree(f.source));
    assertFalse(f.layer.operations.get(2).overrides.containsKey("seedOffset"));
  }

  @Test
  void disabledLayersAndPlacementsDoNotAllocateFieldCompilers() {
    var f = fixture(240); f.layer.enabled = false;
    var registry = new VoxelRegistry(f.source.voxelTypes);
    var layerDisabled = new WorldPlan(f.source, registry, 17);
    assertEquals(0, f.noises.copies);
    assertEquals(0, layerDisabled.sample(1, 2, 3));
    f.layer.enabled = true; f.layer.operations.forEach(op -> op.enabled = false);
    var placementsDisabled = new WorldPlan(f.source, registry, -31);
    assertEquals(0, f.noises.copies);
    assertEquals(0, placementsDisabled.sample(1, 2, 3));
    assertEquals(241, placementsDisabled.stages().size());
  }

  @Test
  void separatePlansRetainIndependentConstructionScopes() {
    var f = fixture(12); var registry = new VoxelRegistry(f.source.voxelTypes);
    var first = new WorldPlan(f.source, registry, 17);
    var second = new WorldPlan(f.source, registry, -31);
    var repeated = new WorldPlan(f.source, registry, 17);
    assertEquals(6, f.noises.copies);
    for (var plan : List.of(first, second, repeated)) assertEquals(0, plan.sample(-7, 2, 9));
    assertEquals(6, f.noises.copies);
  }
}
