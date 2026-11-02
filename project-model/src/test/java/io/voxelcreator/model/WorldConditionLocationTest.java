package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldConditionLocationTest {
  private static ProjectData project() {
    var p = new ProjectData();
    var layer = new Layer();
    layer.id = "layer";
    p.worldGenerator.layers.add(layer);
    return p;
  }

  private static void problem(
      ProjectData p, String code, String path, UUID owner, String parameter) {
    String before = ProjectIO.JSON.valueToTree(p).toString();
    var diagnostic =
        Validator.validate(p).stream()
            .filter(d -> d.code().equals(code) && d.jsonPointer().equals(path))
            .findFirst()
            .orElseThrow();
    assertEquals(owner, diagnostic.sourceUuid());
    assertEquals(parameter, diagnostic.parameterKey());
    assertEquals(before, ProjectIO.JSON.valueToTree(p).toString());
  }

  @Test
  void nestedPredicateReferenceIncludesEveryChildIndexAndTheReferenceLeaf() {
    var p = project();
    var layer = p.worldGenerator.layers.getFirst();
    var root = layer.defaults.voxelStatePredicate;
    root.kind = "OR";
    root.children.add(new Predicate());
    var group = new Predicate();
    group.kind = "AND";
    var missing = new Predicate();
    missing.kind = "TYPE_IS";
    missing.reference = UUID.randomUUID();
    group.children.add(new Predicate());
    group.children.add(missing);
    root.children.add(group);
    problem(
        p,
        "INVALID_REFERENCE",
        "/worldGenerator/layers/0/defaults/voxelStatePredicate/children/1/children/1/reference",
        layer.uuid,
        "reference");
    missing.kind = "NOT_IN_SET";
    problem(
        p,
        "INVALID_REFERENCE",
        "/worldGenerator/layers/0/defaults/voxelStatePredicate/children/1/children/1/reference",
        layer.uuid,
        "reference");
  }

  @Test
  void nestedGateFieldsIdentifyTheExactInvalidControlWithoutChangingCodes() {
    var p = project();
    var layer = p.worldGenerator.layers.getFirst();
    var root = layer.defaults.fieldGates;
    root.kind = "AND";
    var compare = new Gate();
    compare.kind = "COMPARE";
    compare.comparison = "INVALID";
    compare.noiseGeneratorUuid = UUID.randomUUID();
    compare.threshold = Double.NaN;
    compare.max = Double.POSITIVE_INFINITY;
    root.children.add(compare);
    var probability = new Gate();
    probability.kind = "PROBABILITY";
    probability.probability = -1;
    root.children.add(probability);
    String path = "/worldGenerator/layers/0/defaults/fieldGates/children/";
    for (String leaf : List.of("threshold", "max"))
      problem(p, "NONFINITE", path + "0/" + leaf, layer.uuid, leaf);
    problem(
        p, "INVALID_REFERENCE", path + "0/noiseGeneratorUuid", layer.uuid, "noiseGeneratorUuid");
    problem(p, "INVALID_COMPARISON", path + "0/comparison", layer.uuid, "comparison");
    problem(p, "INVALID_PROBABILITY", path + "1/probability", layer.uuid, "probability");
  }

  @Test
  void explicitOverrideKeepsOperationOwnershipAndDepthLimitRetainsItsIndexedPath() {
    var p = project();
    var layer = p.worldGenerator.layers.getFirst();
    var noise = new Noise();
    noise.id = "source";
    p.noise2D.add(noise);
    var operation = new Operation();
    operation.id = "placement";
    operation.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(operation);
    var predicate = new Predicate();
    Predicate cursor = predicate;
    String suffix = "";
    for (int i = 0; i < 33; i++) {
      cursor.kind = "AND";
      cursor.children.add(new Predicate());
      cursor = cursor.children.getFirst();
      suffix += "/children/0";
    }
    operation.overrides.put("voxelStatePredicate", ProjectIO.JSON.valueToTree(predicate));
    problem(
        p,
        "COMPLEXITY_LIMIT",
        "/worldGenerator/layers/0/operations/0/overrides/voxelStatePredicate" + suffix,
        operation.uuid,
        null);
    assertEquals(Set.of("voxelStatePredicate"), operation.overrides.keySet());
  }
}
