package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class ResourceTransactionTest {
  static Noise noise(ProjectData p, String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", value);
    p.noise2D.add(n);
    return n;
  }

  @Test
  void copyingSiblingClosureRemapsInternalAndPreservesExternalReferences() {
    var p = new ProjectData();
    var a = noise(p, "a", 2);
    var b = noise(p, "b", 0);
    var external = noise(p, "external", 3);
    var sample = ProcessingRegistry.create("NOISE_REFERENCE", "sample");
    sample.inputs.put("field", Binding.noiseField(a.uuid));
    b.processing.add(sample);
    var add = ProcessingRegistry.create("ADD", "add");
    add.inputs.put("a", Binding.earlier(sample.uuid));
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = external.uuid;
    add.inputs.put("b", ref);
    b.processing.add(add);
    b.outputBinding = Binding.earlier(add.uuid);
    new ResourceClipboard(p, List.of(a.uuid, b.uuid), false).paste(p, "noise2D", null);
    var copied = p.noise2D.stream().filter(n -> n.id.equals("b-copy")).findFirst().orElseThrow();
    var copiedA = p.noise2D.stream().filter(n -> n.id.equals("a-copy")).findFirst().orElseThrow();
    assertEquals(copiedA.uuid, copied.processing.getFirst().inputs.get("field").noiseGeneratorUuid);
    assertEquals(external.uuid, copied.processing.get(1).inputs.get("b").noiseGeneratorUuid);
    assertEquals(5, new NoiseCompiler(p, 0).compile(copied.uuid).sample(0, 0, 0));
    assertTrue(NoiseCompiler.validate(p).isEmpty());
  }

  @Test
  void folderCopiesOrderAndCutMovesLiveDefinitionsWithoutChangingIdentity() {
    var p = new ProjectData();
    var folder = new Folder();
    folder.id = "folder";
    p.folders.add(folder);
    var dest = new Folder();
    dest.id = "destination";
    p.folders.add(dest);
    var a = noise(p, "a", 1);
    a.parentFolderUuid = folder.uuid;
    folder.childOrder.add(a.uuid);
    new ResourceClipboard(p, List.of(folder.uuid), false).paste(p, "noise2D", null);
    var copy = p.folders.stream().filter(f -> f.id.equals("folder-copy")).findFirst().orElseThrow();
    var copiedNoise =
        p.noise2D.stream()
            .filter(n -> copy.uuid.equals(n.parentFolderUuid))
            .findFirst()
            .orElseThrow();
    assertEquals(List.of(copiedNoise.uuid), copy.childOrder);
    var cut = new ResourceClipboard(p, List.of(a.uuid), true);
    a.algorithm.parameters.put("value", 7.);
    cut.paste(p, "noise2D", dest.uuid);
    assertEquals(dest.uuid, a.parentFolderUuid);
    assertEquals(7, a.algorithm.parameters.get("value"));
    assertTrue(folder.childOrder.isEmpty());
    assertTrue(Validator.validate(p).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceClipboard(p, List.of(dest.uuid), true).paste(p, "noise2D", dest.uuid));
  }

  @Test
  void deletionRewritesMembershipAndPlacementAtomically() {
    var p = new ProjectData();
    var a = new VoxelType();
    a.id = "a";
    var b = new VoxelType();
    b.id = "b";
    p.voxelTypes.addAll(List.of(a, b));
    var set = new Membership();
    set.id = "types";
    set.voxelTypeUuids.addAll(List.of(a.uuid, b.uuid));
    p.voxelSets.add(set);
    var n = noise(p, "n", 1);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = a.uuid;
    var op = new Operation();
    op.id = "op";
    op.noiseGeneratorUuid = n.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var session = new DocumentSession();
    session.setValidator(NoiseCompiler::validate);
    assertTrue(session.edit(q -> ResourceGraph.assign(q, ProjectIO.copy(p))).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> session.edit(q -> ResourceGraph.delete(q, a.uuid, null)));
    assertEquals(2, session.data().voxelTypes.size());
    assertTrue(session.edit(q -> ResourceGraph.delete(q, a.uuid, b.uuid)).isEmpty());
    assertEquals(List.of(b.uuid), session.data().voxelSets.getFirst().voxelTypeUuids);
    assertEquals(b.uuid, session.data().worldGenerator.layers.getFirst().defaults.voxelTypeUuid);
    session.undo();
    assertEquals(a.uuid, session.data().worldGenerator.layers.getFirst().defaults.voxelTypeUuid);
    assertEquals(2, session.data().voxelTypes.size());
  }

  @Test
  void importReuseRequiresSemanticCompatibilityAndSkipClosesDependencies() {
    var destination = new ProjectData();
    var existing = noise(destination, "field", 2);
    var incoming = new ProjectData();
    var source = noise(incoming, "field", 2);
    var dependent = noise(incoming, "dependent", 0);
    var ref = ProcessingRegistry.create("NOISE_REFERENCE", "ref");
    ref.inputs.put("field", Binding.noiseField(source.uuid));
    dependent.processing.add(ref);
    dependent.outputBinding = Binding.earlier(ref.uuid);
    var reuse =
        ResourceImport.plan(
            destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE));
    assertTrue(reuse.valid(), reuse.diagnostics().toString());
    assertEquals(2, reuse.candidate().noise2D.size());
    assertEquals(
        existing.uuid,
        reuse
            .candidate()
            .noise2D
            .getLast()
            .processing
            .getFirst()
            .inputs
            .get("field")
            .noiseGeneratorUuid);
    source.algorithm.parameters.put("value", 3.);
    assertFalse(
        ResourceImport.plan(destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE))
            .valid());
    var skip =
        ResourceImport.plan(destination, incoming, Map.of(source.uuid, ResourceImport.Choice.SKIP));
    assertTrue(skip.valid());
    assertEquals(1, skip.candidate().noise2D.size());
    assertTrue(skip.rows().stream().allMatch(r -> r.resolution().startsWith("SKIP")));
    assertEquals(1, destination.noise2D.size());
  }

  @Test
  void deltasPreserveRedoDirtyStateAndStoreSmallEditsAsSmallRecords() {
    var session = new DocumentSession();
    assertTrue(
        session
            .edit(
                p -> {
                  for (int i = 0; i < 100; i++) noise(p, "noise" + i, 1);
                })
            .isEmpty());
    long baseline = session.historyBytes();
    assertTrue(
        session.edit(p -> p.noise2D.get(50).algorithm.parameters.put("value", 2.)).isEmpty());
    assertTrue(session.historyBytes() - baseline < 200);
    session.undo();
    assertEquals(1, session.data().noise2D.get(50).algorithm.parameters.get("value"));
    session.redo();
    assertEquals(2, session.data().noise2D.get(50).algorithm.parameters.get("value"));
    session.edit(p -> Collections.swap(p.noise2D, 1, 2));
    assertEquals("noise2", session.data().noise2D.get(1).id);
    session.undo();
    assertEquals("noise1", session.data().noise2D.get(1).id);
    session.undo();
    session.undo();
    assertTrue(session.data().noise2D.isEmpty());
    assertFalse(session.dirty());
    session.redo();
    assertEquals(100, session.data().noise2D.size());
  }

  @Test
  void referencesAndSearchDoNotTreatPresentationLabelsAsLinks() {
    var p = new ProjectData();
    var a = noise(p, "a", 1);
    var b = noise(p, "b", 1);
    var pin = new Pin();
    pin.label = a.uuid.toString();
    var parameter = new Parameter();
    parameter.id = "parameter";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    b.sharedParameters.add(parameter);
    pin.targetUuid = parameter.uuid;
    b.pinnedControls.add(pin);
    assertFalse(ResourceGraph.references(b).contains(a.uuid));
    var index = new ResourceSearchIndex(p);
    assertEquals(1, index.search("parameter", "parameter", "All", null).size());
    assertTrue(index.search("", "All", "All", a.uuid).isEmpty());
    var duplicate = ResourceTransactions.duplicate(b, Noise.class);
    assertEquals(a.uuid.toString(), duplicate.pinnedControls.getFirst().label);
    assertNotEquals(parameter.uuid, duplicate.pinnedControls.getFirst().targetUuid);
  }

  @Test
  void stochasticUuidSeedsPreventFalseSemanticReuse() {
    var destination = new ProjectData();
    var existing = noise(destination, "random", 0);
    existing.algorithm.key = "PERLIN";
    var incoming = new ProjectData();
    var source = noise(incoming, "random", 0);
    source.algorithm.key = "PERLIN";
    assertFalse(
        ResourceImport.plan(destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE))
            .valid());
    source.algorithm.key = "CONSTANT";
    existing.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "seed");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("noiseGeneratorSeed"));
    source.processing.add(expression);
    source.outputBinding = Binding.earlier(expression.uuid);
    var copied = ResourceTransactions.duplicate(expression, Item.class);
    existing.processing.add(copied);
    existing.outputBinding = Binding.earlier(copied.uuid);
    assertFalse(
        ResourceImport.plan(destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE))
            .valid());
  }
}
