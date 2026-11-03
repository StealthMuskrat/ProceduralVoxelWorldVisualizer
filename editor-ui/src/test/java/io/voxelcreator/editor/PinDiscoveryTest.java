package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PinDiscoveryTest {
  private ProjectData fixture() {
    var project = ResourceSearchIndexTest.fixture();
    var noise = project.noise2D.getLast();
    var item = noise.processing.getFirst();
    PinControls.add(noise, item.uuid, "inputs/linked", "Linked field");
    PinControls.add(noise, item.uuid, "parameters/expression", "Formula text");
    PinControls.add(noise, noise.sharedParameters.getFirst().uuid, "", "Amplitude");
    noise.pinnedControls.getFirst().group = "Frequent";
    noise.pinnedControls.get(1).group = "Advanced";
    noise.pinnedControls.getLast().group = "Frequent";
    return project;
  }

  private ResourceSearchIndex.Filter filter(String query, UUID reference) {
    return new ResourceSearchIndex.Filter(
        query,
        "pinned control",
        "All",
        "All",
        null,
        reference,
        false,
        ResourceSearchIndex.Mode.ANY_TEXT);
  }

  private ResourceSearchIndex.Hit linked(ResourceSearchIndex index) {
    return index.search(filter("Linked field", null)).getFirst();
  }

  @Test
  void findPinsExposeLabelsGroupsControlNamesAndActualLocations() {
    var project = fixture();
    var noise = project.noise2D.getLast();
    var index = new ResourceSearchIndex(project);
    var rows = index.search(filter("", null));
    assertEquals(3, rows.size());
    assertEquals(2, index.search(filter("frequent", null)).size());
    assertEquals(1, index.search(filter("parameters/expression", null)).size());
    var tree = ProjectIO.JSON.valueToTree(project);
    for (var row : rows) {
      assertEquals(noise.uuid, row.resource());
      assertTrue(row.breadcrumb().contains("continent / mountains / consumer / Pinned Controls"));
      assertEquals(row.label(), tree.at(row.target().pointer()).path("label").textValue());
      assertEquals("pin/label", PinBrowser.navigation(row.target()));
    }
    assertEquals(3, rows.stream().map(ResourceSearchIndex.Hit::key).distinct().count());
  }

  @Test
  void directReferencesListEachPinWithoutAnAggregateNoiseDuplicate() {
    var project = fixture();
    var noise = project.noise2D.getLast();
    UUID target = noise.processing.getFirst().uuid;
    var index = new ResourceSearchIndex(project);
    var direct =
        index.search(
            new ResourceSearchIndex.Filter(
                "", "All", "All", "All", null, target, false, ResourceSearchIndex.Mode.ANY_TEXT));
    assertEquals(2, direct.size());
    assertTrue(direct.stream().allMatch(row -> row.kind().equals("pinned control")));
    assertTrue(
        index
            .search(
                new ResourceSearchIndex.Filter(
                    "", "All", "All", "All", null, target, true, ResourceSearchIndex.Mode.ANY_TEXT))
            .stream()
            .anyMatch(row -> row.kind().equals("noise2D")));
    var absent = UUID.randomUUID();
    noise.pinnedControls.getFirst().label = absent.toString();
    assertTrue(new ResourceSearchIndex(project).search(filter("", absent)).isEmpty());
  }

  @Test
  void pinRowsFollowTheirOriginalEnabledStateAndNoiseDirectory() {
    var project = fixture();
    project.noise2D.getLast().processing.getFirst().enabled = false;
    var index = new ResourceSearchIndex(project);
    var disabled =
        index.search(
            new ResourceSearchIndex.Filter(
                "",
                "pinned control",
                "Disabled",
                "noise2D",
                project.folders.getFirst().uuid,
                null,
                false,
                ResourceSearchIndex.Mode.ANY_TEXT));
    assertEquals(2, disabled.size());
    assertEquals(
        1,
        index
            .search(
                new ResourceSearchIndex.Filter(
                    "",
                    "pinned control",
                    "Enabled",
                    "noise2D",
                    project.folders.getFirst().uuid,
                    null,
                    false,
                    ResourceSearchIndex.Mode.ANY_TEXT))
            .size());
    assertTrue(
        index
            .search(
                new ResourceSearchIndex.Filter(
                    "",
                    "pinned control",
                    "All",
                    "noise3D",
                    null,
                    null,
                    false,
                    ResourceSearchIndex.Mode.ANY_TEXT))
            .isEmpty());
  }

  @Test
  void committedPinRowsOwnMetadataUntilTheNextIndexPublication() {
    var project = fixture();
    var index = new ResourceSearchIndex(project);
    var row = linked(index);
    project.noise2D.getLast().pinnedControls.getFirst().label = "New label";
    project.noise2D.getLast().pinnedControls.getFirst().group = "New group";
    assertEquals("Linked field", row.label());
    assertTrue(index.search(filter("New group", null)).isEmpty());
    assertEquals(1, new ResourceSearchIndex(project).search(filter("New group", null)).size());
    assertThrows(UnsupportedOperationException.class, () -> row.references().clear());
  }

  @Test
  void logicalRoutesAndDistinctPinKeysSurviveReorderAndMetadataChanges() {
    var project = fixture();
    var noise = project.noise2D.getLast();
    var selected = noise.pinnedControls.getFirst();
    var original = linked(new ResourceSearchIndex(project));
    Collections.reverse(noise.pinnedControls);
    selected.label = "Renamed pin";
    selected.group = "Moved group";
    var current = new ResourceSearchIndex(project).search(filter("Renamed pin", null)).getFirst();
    assertEquals(original.key(), current.key());
    assertNotEquals(original.target().pointer(), current.target().pointer());
    var browser = new PinBrowser();
    browser.synchronize(noise, 1);
    assertTrue(
        browser.selectTarget(
            original.target().control(), PinBrowser.controlPath(original.target())));
    assertSame(selected, browser.selected());
    assertEquals("group:Moved group", browser.group());
  }

  @Test
  void duplicateLogicalPinsHaveSeparateRowsButCannotSilentlyChooseOne() {
    var project = fixture();
    var noise = project.noise2D.getLast();
    var duplicate = ProjectIO.JSON.convertValue(noise.pinnedControls.getFirst(), Pin.class);
    noise.pinnedControls.add(duplicate);
    var rows = new ResourceSearchIndex(project).search(filter("", null));
    assertEquals(4, rows.size());
    assertEquals(4, rows.stream().map(ResourceSearchIndex.Hit::key).distinct().count());
    var browser = new PinBrowser();
    browser.synchronize(noise, 0);
    assertTrue(browser.selectTarget(noise.sharedParameters.getFirst().uuid, ""));
    var previous = browser.selected();
    assertFalse(browser.selectTarget(duplicate.targetUuid, duplicate.control));
    assertSame(previous, browser.selected());
    assertEquals("all", browser.group());
  }

  @Test
  void removedOrRetargetedPinRoutesRevealChoicesWithoutSelectingANeighbor() {
    var project = fixture();
    var noise = project.noise2D.getLast();
    var target = linked(new ResourceSearchIndex(project)).target();
    var browser = new PinBrowser();
    noise.pinnedControls.removeFirst();
    browser.synchronize(noise, 1);
    var previous = browser.selected();
    assertFalse(browser.selectTarget(target.control(), PinBrowser.controlPath(target)));
    assertSame(previous, browser.selected());
    assertEquals("pin/label", PinBrowser.navigation(target));
  }

  @Test
  void usedByCarriesTheExactPinPathAndRetainsImmutableSnapshotMetadata() {
    var project = fixture();
    var noise = project.noise2D.getLast();
    var item = noise.processing.getFirst();
    var index = new NoiseDraftIndex(noise);
    var uses = index.users(item.uuid);
    assertEquals(2, uses.size());
    var use =
        uses.stream()
            .filter(value -> value.label().equals("Pin: Linked field"))
            .findFirst()
            .orElseThrow();
    var target =
        new EditorEvents.Target(
            project.project.uuid, noise.uuid, use.owner(), use.path(), "", false);
    assertEquals("inputs/linked", PinBrowser.controlPath(target));
    Collections.reverse(noise.pinnedControls);
    noise.pinnedControls.getLast().label = "Changed";
    var browser = new PinBrowser();
    browser.synchronize(noise, 1);
    assertTrue(browser.selectTarget(use.owner(), PinBrowser.controlPath(target)));
    assertSame(noise.pinnedControls.getLast(), browser.selected());
    assertEquals("Pin: Linked field", use.label());
    assertThrows(UnsupportedOperationException.class, () -> uses.clear());
  }

  @Test
  void logicalRoutesRoundTripWholeItemAndCompleteUnicodeControlNames() {
    for (String control : List.of("", "value", "inputs/a/b~雪", "parameters/long/control")) {
      var pin = new Pin();
      pin.control = control;
      var target =
          new EditorEvents.Target(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              PinBrowser.parameter(pin),
              "/noise2D/9/pinnedControls/99",
              false);
      assertEquals(control, PinBrowser.controlPath(target));
      assertEquals("pin/label", PinBrowser.navigation(target));
    }
    assertNull(PinBrowser.controlPath(null));
  }

  @Test
  void centralPinHelpIsSearchableLinkedAndReadOnly() {
    var metadata = AppMetadata.defaults();
    var before = ProjectIO.JSON.valueToTree(metadata);
    var session = new HelpSession();
    session.synchronize(metadata);
    var catalog = session.catalog();
    var topic = catalog.topic("help.pins");
    assertTrue(catalog.search("pinned literal").contains(topic));
    assertTrue(topic.related().containsAll(List.of("noise", "processing", "help.find", "save")));
    assertTrue(catalog.topic("noise").related().contains(topic.id()));
    assertTrue(session.open(topic.id()));
    String copy = topic.copyText(catalog);
    assertTrue(
        copy.contains("Bound inputs")
            && copy.contains("Find References")
            && copy.contains("Apply"));
    assertEquals(before, ProjectIO.JSON.valueToTree(metadata));
  }
}
