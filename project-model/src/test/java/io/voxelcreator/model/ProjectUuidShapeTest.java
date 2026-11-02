package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred typed UUID admission: user map keys are not resource field declarations. */
class ProjectUuidShapeTest {
  @TempDir Path directory;

  private ProjectData fixture() {
    var p = new ProjectData();
    var type = new VoxelType();
    type.id = "stone";
    type.uuid = UUID.fromString("00000001-0001-0001-0001-000000000001");
    p.voxelTypes.add(type);
    for (var membership : List.of(p.voxelGroups, p.voxelSets)) {
      var group = new Membership();
      group.id = "members";
      group.voxelTypeUuids.add(type.uuid);
      membership.add(group);
    }
    var folder = new Folder();
    folder.id = "folder";
    folder.uuid = UUID.fromString("abcdef01-abcd-abcd-abcd-abcdef012345");
    p.folders.add(folder);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(folder.uuid)));
    var noise = new Noise();
    noise.id = "field";
    noise.uuid = UUID.fromString("00000002-0002-0002-0002-000000000002");
    noise.parentFolderUuid = folder.uuid;
    folder.childOrder.add(noise.uuid);
    var item = new Item();
    item.id = "expression";
    item.kind = "EXPRESSION";
    item.inputs.put("uuid", Binding.constant(1));
    item.inputs.put("reference", Binding.constant(2));
    item.inputs.put("targetUuid", Binding.constant(3));
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("uuid + reference + targetUuid"));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "placement";
    operation.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  private String base64(UUID id) {
    return Base64.getEncoder()
        .encodeToString(
            ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
  }

  private void rejected(ObjectNode tree, String path) throws Exception {
    var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree));
    assertFalse(result.success());
    assertNull(result.project());
    assertEquals(1, result.diagnostics().size());
    assertEquals("INVALID_STRUCTURE", result.diagnostics().getFirst().code());
    assertEquals(path, result.diagnostics().getFirst().jsonPointer());
    assertFalse(tree.at(path).isMissingNode());
  }

  @Test
  void inputNamesThatLookLikeIdentityFieldsRoundTripAsBindingMaps() throws Exception {
    var p = fixture();
    var expected = ProjectIO.JSON.valueToTree(p);
    var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(p));
    assertTrue(result.success(), result.diagnostics().toString());
    assertEquals(expected, ProjectIO.JSON.valueToTree(result.project()));
    assertEquals(
        Set.of("uuid", "reference", "targetUuid"),
        result.project().noise2D.getFirst().processing.getFirst().inputs.keySet());
  }

  @Test
  void everyUuidCollectionRejectsAlternativeSpellingsAndNonStringElements() throws Exception {
    var p = fixture();
    for (String path :
        List.of(
            "/voxelGroups/0/voxelTypeUuids/0",
            "/voxelSets/0/voxelTypeUuids/0",
            "/folders/0/childOrder/0",
            "/project/rootOrder/noise2D/0")) {
      ObjectNode original = ProjectIO.JSON.valueToTree(p);
      var id = UUID.fromString(original.at(path).textValue());
      for (JsonNode value :
          List.of(
              TextNode.valueOf(base64(id)),
              TextNode.valueOf("1-1-1-1-1"),
              TextNode.valueOf("{" + id + "}"),
              IntNode.valueOf(1),
              NullNode.instance,
              TextNode.valueOf(" " + id))) {
        ObjectNode tree = original.deepCopy();
        ((ArrayNode) tree.at(path.substring(0, path.lastIndexOf('/')))).set(0, value);
        rejected(tree, path);
      }
    }
  }

  @Test
  void fullHexadecimalCaseAndNullableScalarReferencesKeepTheirExistingMeaning() throws Exception {
    var p = fixture();
    ObjectNode tree = ProjectIO.JSON.valueToTree(p);
    ((ObjectNode) tree.path("folders").get(0))
        .put("uuid", p.folders.getFirst().uuid.toString().toUpperCase(Locale.ROOT));
    assertTrue(tree.path("folders").get(0).path("parentFolderUuid").isNull());
    var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree));
    assertTrue(result.success(), result.diagnostics().toString());
    assertEquals(ProjectIO.JSON.valueToTree(p), ProjectIO.JSON.valueToTree(result.project()));
  }

  @Test
  void bindingValuesStillValidateTheirDeclaredUuidFieldsUnderIdentityLikeMapKeys()
      throws Exception {
    ObjectNode tree = ProjectIO.JSON.valueToTree(fixture());
    var input =
        (ObjectNode)
            tree.path("noise2D").get(0).path("processing").get(0).path("inputs").path("uuid");
    input.put("noiseGeneratorUuid", base64(UUID.randomUUID()));
    rejected(tree, "/noise2D/0/processing/0/inputs/uuid/noiseGeneratorUuid");
  }

  @Test
  void sparseOverrideObjectsRetainTypedUuidValidationThroughNestedSettings() throws Exception {
    for (String property : List.of("voxelTypeUuid", "typeIntervals", "fieldGates")) {
      ObjectNode tree = ProjectIO.JSON.valueToTree(fixture());
      var overrides =
          (ObjectNode)
              tree.path("worldGenerator")
                  .path("layers")
                  .get(0)
                  .path("operations")
                  .get(0)
                  .path("overrides");
      String path = "/worldGenerator/layers/0/operations/0/overrides/" + property;
      if (property.equals("voxelTypeUuid")) overrides.put(property, "1-1-1-1-1");
      else if (property.equals("typeIntervals")) {
        var interval = new TypeInterval();
        ObjectNode value = ProjectIO.JSON.valueToTree(interval);
        value.put("voxelTypeUuid", "1-1-1-1-1");
        overrides.set(property, ProjectIO.JSON.createArrayNode().add(value));
        path += "/0/voxelTypeUuid";
      } else {
        ObjectNode gate = ProjectIO.JSON.valueToTree(new Gate());
        gate.put("noiseGeneratorUuid", "1-1-1-1-1");
        overrides.set(property, gate);
        path += "/noiseGeneratorUuid";
      }
      rejected(tree, path);
    }
  }

  @Test
  void directOverrideAdmissionRejectsNoncanonicalReferencesWithoutRewritingThem() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    var operation = layer.operations.getFirst();
    operation.overrides.put(
        "voxelTypeUuid", TextNode.valueOf(base64(p.voxelTypes.getFirst().uuid)));
    var before = ProjectIO.JSON.valueToTree(p);
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> SettingsResolver.resolve(layer, operation));
    assertTrue(failure.getMessage().contains("/voxelTypeUuid"));
    assertTrue(Validator.validate(p).stream().anyMatch(d -> d.code().equals("INVALID_OVERRIDE")));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void curveJsonParametersValidateActualCurveAndPointIdentities() throws Exception {
    var p = fixture();
    var curve = new Curve();
    var item = new Item();
    item.id = "curve";
    item.kind = "CURVE";
    item.enabled = false;
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    p.noise2D.getFirst().processing.add(item);
    for (String leaf : List.of("/uuid", "/points/0/uuid")) {
      ObjectNode tree = ProjectIO.JSON.valueToTree(p);
      var value =
          (ObjectNode)
              tree.path("noise2D")
                  .get(0)
                  .path("processing")
                  .get(1)
                  .path("parameters")
                  .path("curve");
      var parent = (ObjectNode) (leaf.equals("/uuid") ? value : value.path("points").get(0));
      parent.put("uuid", "1-1-1-1-1");
      rejected(tree, "/noise2D/0/processing/1/parameters/curve" + leaf);
    }
    var value = (ObjectNode) item.parameters.get("curve");
    ((ObjectNode) value.path("points").get(0)).put("uuid", "1-1-1-1-1");
    assertTrue(
        Validator.validate(p).stream()
            .anyMatch(d -> d.code().equals("MISSING_UUID") || d.code().equals("INVALID_CURVE")));
  }

  @Test
  void rawProcessingReferenceParametersDoNotUsePermissiveUuidParsing() throws Exception {
    var p = fixture();
    var item = new Item();
    item.id = "reference-item";
    item.kind = "NOISE_REFERENCE";
    item.enabled = false;
    item.parameters.put("noiseGeneratorUuid", TextNode.valueOf("2-2-2-2-2"));
    p.noise2D.getFirst().processing.add(item);
    rejected(
        ProjectIO.JSON.valueToTree(p), "/noise2D/0/processing/1/parameters/noiseGeneratorUuid");
    assertTrue(
        Validator.validate(p).stream()
            .anyMatch(
                d ->
                    d.code().equals("INVALID_REFERENCE")
                        && d.jsonPointer().endsWith("/parameters/noiseGeneratorUuid")));
  }

  @Test
  void identityNamedInputBindingsParticipateInDependencyClosureAndSafeDeletion() throws Exception {
    var p = fixture();
    var field = p.noise2D.getFirst();
    var external = new Noise();
    external.id = "external";
    var replacement = new Noise();
    replacement.id = "replacement";
    p.noise2D.addAll(List.of(external, replacement));
    for (String key : List.of("uuid", "childOrder")) {
      var binding = new Binding();
      binding.sourceKind = "Noise Generator Reference";
      binding.noiseGeneratorUuid = external.uuid;
      field.processing.getFirst().inputs.put(key, binding);
    }
    assertTrue(ResourceGraph.references(field).contains(external.uuid));
    assertTrue(
        ResourceGraph.dependents(p, Set.of(external.uuid)).stream()
            .anyMatch(e -> e.uuid().equals(field.uuid)));
    var subset = ResourceGraph.selection(p, List.of(field.uuid), true);
    assertEquals(
        Set.of(field.uuid, external.uuid),
        new HashSet<>(subset.noise2D.stream().map(n -> n.uuid).toList()));
    var before = ProjectIO.JSON.valueToTree(p);
    assertThrows(
        IllegalArgumentException.class, () -> ResourceGraph.delete(p, external.uuid, null));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
    ResourceGraph.delete(p, external.uuid, replacement.uuid);
    var changed =
        p.noise2D.stream().filter(n -> n.uuid.equals(field.uuid)).findFirst().orElseThrow();
    assertEquals(
        replacement.uuid, changed.processing.getFirst().inputs.get("uuid").noiseGeneratorUuid);
    assertEquals(
        replacement.uuid,
        changed.processing.getFirst().inputs.get("childOrder").noiseGeneratorUuid);
    assertTrue(new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(p)).success());
  }

  @Test
  void failedReferenceArrayOpenPreservesSavedStateHistoryAndOwnedSession() throws Exception {
    var document = new DocumentSession();
    assertTrue(document.edit(p -> ResourceGraph.assign(p, fixture())).isEmpty());
    var saved = directory.resolve("original.json");
    document.save(saved);
    assertTrue(document.edit(p -> p.project.seed = 29).isEmpty());
    var active = document.data();
    var token = document.sessionToken();
    var before = ProjectIO.JSON.valueToTree(active);
    long history = document.historyBytes();
    ObjectNode tree = ProjectIO.JSON.valueToTree(active);
    ((ArrayNode) tree.path("voxelSets").get(0).path("voxelTypeUuids"))
        .set(0, TextNode.valueOf(base64(active.voxelTypes.getFirst().uuid)));
    var candidate = directory.resolve("candidate.json");
    Files.writeString(candidate, ProjectIO.JSON.writeValueAsString(tree));
    assertEquals(
        "/voxelSets/0/voxelTypeUuids/0", document.open(candidate).getFirst().jsonPointer());
    assertSame(active, document.data());
    assertSame(token, document.sessionToken());
    assertEquals(saved, document.path());
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    assertEquals(history, document.historyBytes());
    assertTrue(document.dirty());
    document.undo();
    assertFalse(document.dirty());
    document.redo();
    assertTrue(document.dirty());
  }
}
