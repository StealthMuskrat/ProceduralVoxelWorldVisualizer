package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceSearchIndexTest {
  static ProjectData fixture() {
    var p = new ProjectData();
    var outer = new Folder();
    outer.id = "continent";
    outer.rootKind = "noise2D";
    var inner = new Folder();
    inner.id = "mountains";
    inner.rootKind = "noise2D";
    inner.parentFolderUuid = outer.uuid;
    p.folders.addAll(List.of(outer, inner));
    var source = new Noise();
    source.id = "source";
    source.algorithm.key = "CONSTANT";
    source.algorithm.parameters.clear();
    source.algorithm.parameters.put("value", .25);
    p.noise2D.add(source);
    var consumer = new Noise();
    consumer.id = "consumer";
    consumer.parentFolderUuid = inner.uuid;
    var expression = ProcessingRegistry.create("EXPRESSION", "formula");
    expression.inputs.clear();
    var binding = new Binding();
    binding.sourceKind = "Noise Generator Reference";
    binding.noiseGeneratorUuid = source.uuid;
    expression.inputs.put("linked", binding);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("linked + 0.5"));
    consumer.processing.add(expression);
    var curve = ProcessingRegistry.create("CURVE", "response");
    curve.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
    consumer.processing.add(curve);
    var parameter = new Parameter();
    parameter.id = "amplitude";
    parameter.value = ProjectIO.JSON.valueToTree(2.5);
    consumer.sharedParameters.add(parameter);
    p.noise2D.add(consumer);
    var layer = new Layer();
    layer.id = "terrain";
    layer.enabled = false;
    var op = new Operation();
    op.id = "fill";
    op.noiseGeneratorUuid = source.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  static ResourceSearchIndex.Filter filter(
      String text,
      String kind,
      String root,
      UUID directory,
      UUID reference,
      boolean containers,
      ResourceSearchIndex.Mode mode) {
    return new ResourceSearchIndex.Filter(
        text, kind, "All", root, directory, reference, containers, mode);
  }

  @Test
  void rootDirectoryAndBreadcrumbFiltersKeepExactHierarchyContext() {
    var p = fixture();
    var index = new ResourceSearchIndex(p);
    var rows =
        index.search(
            filter(
                "",
                "noise2D",
                "noise2D",
                p.folders.getFirst().uuid,
                null,
                false,
                ResourceSearchIndex.Mode.ANY_TEXT));
    assertEquals(List.of("consumer"), rows.stream().map(ResourceSearchIndex.Hit::label).toList());
    assertEquals("noise2D / continent / mountains / consumer", rows.getFirst().breadcrumb());
    assertEquals(
        List.of("consumer"),
        index
            .search(
                filter(
                    "continent / mountains",
                    "noise2D",
                    "All",
                    null,
                    null,
                    false,
                    ResourceSearchIndex.Mode.BREADCRUMB))
            .stream()
            .map(ResourceSearchIndex.Hit::label)
            .toList());
    assertTrue(
        index
            .search(
                filter(
                    "continent", "noise2D", "All", null, null, false, ResourceSearchIndex.Mode.ID))
            .isEmpty());
  }

  @Test
  void directReferencesIdentifyBindingsAndOperationsWithoutContainerDuplicates() {
    var p = fixture();
    var index = new ResourceSearchIndex(p);
    UUID source = p.noise2D.getFirst().uuid;
    var direct =
        index.search(
            filter("", "All", "All", null, source, false, ResourceSearchIndex.Mode.ANY_TEXT));
    assertEquals(
        Set.of("input binding", "operation"),
        new HashSet<>(direct.stream().map(ResourceSearchIndex.Hit::kind).toList()));
    var containers =
        index.search(
            filter("", "All", "All", null, source, true, ResourceSearchIndex.Mode.ANY_TEXT));
    assertTrue(containers.size() > direct.size());
    assertTrue(containers.stream().anyMatch(h -> h.kind().equals("processing")));
    assertEquals(
        1,
        index
            .search(
                filter(
                    "linked",
                    "input binding",
                    "noise2D",
                    null,
                    source,
                    false,
                    ResourceSearchIndex.Mode.ID))
            .size());
  }

  @Test
  void presentationUuidStringsAreSearchableTextButNeverInventReferences() {
    var p = fixture();
    UUID absent = UUID.randomUUID();
    var pin = new Pin();
    pin.label = absent.toString();
    pin.targetUuid = p.noise2D.getLast().sharedParameters.getFirst().uuid;
    p.noise2D.getLast().pinnedControls.add(pin);
    var index = new ResourceSearchIndex(p);
    assertTrue(index.search("", "All", "All", absent).isEmpty());
    assertFalse(index.search(absent.toString(), "All", "All", null).isEmpty());
  }

  @Test
  void sourceParametersBindingsAndCurvePointsHaveRealJsonLocationsAndUniqueKeys() {
    var p = fixture();
    var tree = ProjectIO.JSON.valueToTree(p);
    var index = new ResourceSearchIndex(p);
    var hits = index.search(ResourceSearchIndex.Filter.all());
    assertEquals(hits.size(), hits.stream().map(ResourceSearchIndex.Hit::key).distinct().count());
    for (var hit : hits)
      assertFalse(
          tree.at(hit.target().pointer()).isMissingNode(),
          hit.key() + " / " + hit.target().pointer());
    var binding =
        hits.stream().filter(h -> h.kind().equals("input binding")).findFirst().orElseThrow();
    assertEquals(p.noise2D.getLast().processing.getFirst().uuid, binding.target().control());
    assertEquals("inputs/linked", binding.target().parameter());
    assertEquals(2, hits.stream().filter(h -> h.kind().equals("curve point")).count());
  }

  @Test
  void parameterMapReferencesRetainTheirKeyContextAndPointersEscapeReservedCharacters() {
    var p = fixture();
    var item = p.noise2D.getLast().processing.getFirst();
    var source = p.noise2D.getFirst().uuid;
    item.parameters.put("noiseGeneratorUuid", ProjectIO.JSON.valueToTree(source));
    item.parameters.put("value/~", ProjectIO.JSON.valueToTree(7));
    var index = new ResourceSearchIndex(p);
    assertEquals(
        1,
        index
            .search(
                filter(
                    "noiseGeneratorUuid",
                    "item parameter",
                    "All",
                    null,
                    source,
                    false,
                    ResourceSearchIndex.Mode.ID))
            .size());
    var target =
        index
            .search(
                filter(
                    "value/~",
                    "item parameter",
                    "All",
                    null,
                    null,
                    false,
                    ResourceSearchIndex.Mode.ID))
            .getFirst()
            .target();
    assertTrue(target.pointer().endsWith("value~1~0"));
    assertEquals(7, ProjectIO.JSON.valueToTree(p).at(target.pointer()).intValue());
  }

  @Test
  void disabledLayerFlagsPropagateToOperationHitsAndDisabledItemsToChildControls() {
    var p = fixture();
    p.noise2D.getLast().processing.getFirst().enabled = false;
    var index = new ResourceSearchIndex(p);
    var disabled =
        index.search(
            new ResourceSearchIndex.Filter(
                "",
                "All",
                "Disabled",
                "All",
                null,
                null,
                false,
                ResourceSearchIndex.Mode.ANY_TEXT));
    assertTrue(disabled.stream().anyMatch(h -> h.kind().equals("operation")));
    assertTrue(disabled.stream().anyMatch(h -> h.kind().equals("input binding")));
    assertTrue(
        index
            .search(
                new ResourceSearchIndex.Filter(
                    "",
                    "operation",
                    "Enabled",
                    "All",
                    null,
                    null,
                    false,
                    ResourceSearchIndex.Mode.ANY_TEXT))
            .isEmpty());
  }

  @Test
  void immutableSnapshotsRetainLabelsValuesAndReferenceSetsAfterSourceMutation() {
    var p = fixture();
    var index = new ResourceSearchIndex(p);
    var before = index.search(ResourceSearchIndex.Filter.all());
    p.noise2D.getLast().id = "changed";
    p.noise2D.getLast().processing.clear();
    assertEquals(before, index.search(ResourceSearchIndex.Filter.all()));
    assertThrows(UnsupportedOperationException.class, () -> before.clear());
    var hit = before.stream().filter(h -> !h.references().isEmpty()).findFirst().orElseThrow();
    assertThrows(UnsupportedOperationException.class, () -> hit.references().clear());
  }

  @Test
  void filtersUseRootLocaleAndRejectUnknownContractValues() {
    var p = fixture();
    var index = new ResourceSearchIndex(p);
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals(
          1,
          index
              .search(
                  filter(
                      "AMPLITUDE",
                      "parameter",
                      "All",
                      null,
                      null,
                      false,
                      ResourceSearchIndex.Mode.ID))
              .size());
    } finally {
      Locale.setDefault(previous);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> filter("", "unknown", "All", null, null, false, ResourceSearchIndex.Mode.ID));
  }

  @Test
  void deepFolderPathsAreIterativeBoundedAndDirectoryScopesRemainExact() {
    var p = new ProjectData();
    UUID parent = null;
    for (int i = 0; i < 1500; i++) {
      var f = new Folder();
      f.id = "directory-" + i;
      f.rootKind = "noise2D";
      f.parentFolderUuid = parent;
      p.folders.add(f);
      parent = f.uuid;
    }
    var n = new Noise();
    n.id = "deep-field";
    n.parentFolderUuid = parent;
    p.noise2D.add(n);
    var index = new ResourceSearchIndex(p);
    var result =
        index.search(
            filter(
                "",
                "noise2D",
                "All",
                p.folders.getFirst().uuid,
                null,
                false,
                ResourceSearchIndex.Mode.ID));
    assertEquals(1, result.size());
    assertTrue(result.getFirst().breadcrumb().length() <= 4096);
    assertTrue(result.getFirst().breadcrumb().endsWith("deep-field"));
  }

  @Test
  void directoryChoiceCatalogIncludesFoldersAndLayersWithoutResourceIdentityMixups() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var choices = new ResourceChoices().rows(index, "directory", "");
    assertEquals(3, choices.size());
    assertTrue(choices.stream().allMatch(n -> ResourceChoices.accepts(n, "directory")));
    assertFalse(ResourceChoices.accepts(index.get(p.noise2D.getFirst().uuid), "directory"));
  }
}
