package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred conservative Reuse comparisons must retain every user input binding. */
class ResourceImportCompatibilityTest {
  private static final List<String> INPUTS =
      List.of("uuid", "id", "parentFolderUuid", "targetUuid", "reference");

  private ProjectData fixture() {
    var p = new ProjectData();
    var noise = new Noise();
    noise.id = "field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 0.0);
    var item = new Item();
    item.id = "formula";
    item.kind = "EXPRESSION";
    for (int i = 0; i < INPUTS.size(); i++) item.inputs.put(INPUTS.get(i), Binding.constant(i + 1));
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(String.join(" + ", INPUTS)));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(noise);
    return p;
  }

  private boolean compatible(ProjectData incoming, ProjectData destination) {
    return ResourceImport.compatible(
        ResourceGraph.find(incoming, incoming.noise2D.getFirst().uuid),
        incoming,
        ResourceGraph.find(destination, destination.noise2D.getFirst().uuid),
        destination);
  }

  private ResourceImport.Row row(ResourceImport.Plan plan, UUID id) {
    return plan.rows().stream().filter(r -> r.uuid().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void eachIdentityLikeInputValueParticipatesInCompatibilityAndReuseAdmission() {
    for (boolean sameIdentity : List.of(false, true))
      for (String key : INPUTS) {
        var destination = fixture();
        var incoming = fixture();
        var source = incoming.noise2D.getFirst();
        if (sameIdentity) source.uuid = destination.noise2D.getFirst().uuid;
        source.processing.getFirst().inputs.put(key, Binding.constant(99));
        var beforeDestination = ProjectIO.JSON.valueToTree(destination);
        var beforeIncoming = ProjectIO.JSON.valueToTree(incoming);
        assertFalse(compatible(incoming, destination), key);
        var plan =
            ResourceImport.plan(
                destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE));
        assertFalse(plan.valid(), key);
        assertFalse(row(plan, source.uuid).compatible());
        assertTrue(
            plan.diagnostics().stream().anyMatch(d -> d.code().equals("INCOMPATIBLE_REUSE")));
        assertEquals(beforeDestination, ProjectIO.JSON.valueToTree(destination));
        assertEquals(beforeIncoming, ProjectIO.JSON.valueToTree(incoming));
      }
  }

  @Test
  void identicalBindingsReuseAcrossLocalIdentityAndOrganizationChanges() {
    var destination = fixture();
    var incoming = fixture();
    for (var p : List.of(destination, incoming)) {
      var folder = new Folder();
      folder.id = "organization";
      p.folders.add(folder);
      p.noise2D.getFirst().parentFolderUuid = folder.uuid;
    }
    assertTrue(compatible(incoming, destination));
    var source = incoming.noise2D.getFirst();
    var plan =
        ResourceImport.plan(
            destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertEquals(destination.noise2D.getFirst().uuid, row(plan, source.uuid).targetUuid());
    assertEquals(1, plan.candidate().noise2D.size());
    assertEquals(
        new HashSet<>(INPUTS),
        plan.candidate().noise2D.getFirst().processing.getFirst().inputs.keySet());
  }

  @Test
  void sharedParameterReferencesNormalizeTheirActualLocalIdentities() {
    var destination = fixture();
    var incoming = fixture();
    for (var p : List.of(destination, incoming)) {
      var noise = p.noise2D.getFirst();
      var parameter = new Parameter();
      parameter.id = "parameter";
      parameter.value = ProjectIO.JSON.valueToTree(1.0);
      noise.sharedParameters.add(parameter);
      var binding = new Binding();
      binding.sourceKind = "Shared Parameter";
      binding.parameterUuid = parameter.uuid;
      noise.processing.getFirst().inputs.put("uuid", binding);
    }
    assertTrue(compatible(incoming, destination));
    incoming.noise2D.getFirst().sharedParameters.getFirst().value = ProjectIO.JSON.valueToTree(2.0);
    assertFalse(compatible(incoming, destination));
  }

  @Test
  void externalReferencesUnderIdentityLikeNamesCannotBeDroppedByCanonicalization() {
    var destination = fixture();
    var incoming = fixture();
    for (var p : List.of(destination, incoming)) {
      var external = new Noise();
      external.id = "external";
      p.noise2D.add(external);
      var binding = new Binding();
      binding.sourceKind = "Noise Generator Reference";
      binding.noiseGeneratorUuid = external.uuid;
      p.noise2D.getFirst().processing.getFirst().inputs.put("uuid", binding);
    }
    assertFalse(compatible(incoming, destination));
    var expected = destination.noise2D.getLast().uuid;
    var external = incoming.noise2D.getLast();
    external.uuid = expected;
    incoming.noise2D.getFirst().processing.getFirst().inputs.get("uuid").noiseGeneratorUuid =
        expected;
    assertTrue(compatible(incoming, destination));
  }

  @Test
  void removingAnUnusedIdentityNamedInputIsStillAVisibleDefinitionDifference() {
    var destination = fixture();
    var incoming = fixture();
    for (var p : List.of(destination, incoming)) {
      var item = p.noise2D.getFirst().processing.getFirst();
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("1"));
      item.inputs.clear();
    }
    incoming.noise2D.getFirst().processing.getFirst().inputs.put("uuid", Binding.constant(1));
    assertFalse(compatible(incoming, destination));
  }

  @Test
  void curveIdentityNormalizationRetainsPointValuesAndReferenceStructure() {
    var destination = fixture();
    var incoming = fixture();
    for (var p : List.of(destination, incoming)) {
      var item = p.noise2D.getFirst().processing.getFirst();
      item.kind = "CURVE";
      item.inputs.clear();
      item.inputs.put("a", Binding.source());
      item.parameters.clear();
      item.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
    }
    assertTrue(compatible(incoming, destination));
    var curve = incoming.noise2D.getFirst().processing.getFirst().parameters.get("curve");
    ((com.fasterxml.jackson.databind.node.ObjectNode) curve.path("points").get(1)).put("y", .75);
    assertFalse(compatible(incoming, destination));
  }

  @Test
  void rejectedReuseCanSwitchToStableCopyAndPublishOneOwnedTransaction() {
    var document = new DocumentSession();
    assertTrue(document.edit(p -> ResourceGraph.assign(p, fixture())).isEmpty());
    var incoming = fixture();
    var source = incoming.noise2D.getFirst();
    source.processing.getFirst().inputs.put("uuid", Binding.constant(11));
    var session = new ResourceImport.Session(document.data(), incoming);
    var copy = row(session.preview(), source.uuid).targetUuid();
    var active = document.data();
    var token = document.sessionToken();
    var before = ProjectIO.JSON.valueToTree(active);
    long history = document.historyBytes();
    session.choose(source.uuid, ResourceImport.Choice.REUSE);
    assertFalse(session.preview().valid());
    assertThrows(IllegalStateException.class, () -> document.edit(session::applyTo));
    assertSame(active, document.data());
    assertSame(token, document.sessionToken());
    assertEquals(history, document.historyBytes());
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    session.choose(source.uuid, ResourceImport.Choice.COPY);
    assertEquals(copy, row(session.preview(), source.uuid).targetUuid());
    var expected = ProjectIO.JSON.valueToTree(session.preview().candidate());
    assertTrue(document.edit(session::applyTo).isEmpty());
    assertEquals(expected, ProjectIO.JSON.valueToTree(document.data()));
    assertEquals("field-copy", document.data().noise2D.getLast().id);
    assertEquals(
        11,
        document
            .data()
            .noise2D
            .getLast()
            .processing
            .getFirst()
            .inputs
            .get("uuid")
            .value
            .intValue());
    document.undo();
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    document.redo();
    assertEquals(expected, ProjectIO.JSON.valueToTree(document.data()));
  }

  @Test
  void previewRowsCannotBecomeCompatibleByChangingAnAccessorSnapshot() {
    var destination = fixture();
    var incoming = fixture();
    var source = incoming.noise2D.getFirst();
    source.processing.getFirst().inputs.put("id", Binding.constant(12));
    var session = new ResourceImport.Session(destination, incoming);
    session.choose(source.uuid, ResourceImport.Choice.REUSE);
    var preview = session.preview();
    assertFalse(preview.valid());
    preview.candidate().noise2D.clear();
    source.processing.getFirst().inputs.put("id", Binding.constant(2));
    assertSame(preview, session.preview());
    assertFalse(row(session.preview(), source.uuid).compatible());
    assertFalse(session.preview().valid());
  }
}
