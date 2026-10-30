package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HelpCatalogTest {
  @Test
  void activePresentationAndCanonicalOptionsAreBothSearchableWithoutLosingRegistryIdentity() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("noise2D.algorithm").options.put("CONSTANT", "Flat snow 雪");
    metadata.controls.get("noise2D.algorithm").label = "Authored source###name";
    metadata.controls.get("noise").label = "Unique related beacon";
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("noise2D.algorithm");
    assertTrue(catalog.search("constant flat 雪").contains(topic));
    assertTrue(catalog.search("authored source###name").contains(topic));
    assertTrue(catalog.search("related beacon").contains(topic));
    assertEquals(
        CreationRegistry.CONTROLS.get("noise2D.algorithm").options(),
        topic.options().stream().map(HelpCatalog.Option::key).toList());
    assertEquals(
        "Flat snow 雪",
        topic.options().stream()
            .filter(o -> o.key().equals("CONSTANT"))
            .findFirst()
            .orElseThrow()
            .label());
  }

  @Test
  void completeCopyIncludesGuidanceOptionsAndResolvedRelatedTopicsWithoutUiEscaping() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("noise2D.algorithm").label = "Source###snow 雪";
    metadata.controls.get("noise2D.algorithm").description =
        "full guidance\n" + "detail ".repeat(800);
    var catalog = new HelpCatalog(metadata);
    String copy = catalog.topic("noise2D.algorithm").copyText(catalog);
    assertTrue(copy.startsWith("Source###snow 雪\nTopic: noise2D.algorithm"));
    assertTrue(copy.contains(metadata.controls.get("noise2D.algorithm").description));
    assertTrue(copy.contains("CONSTANT — CONSTANT"));
    assertTrue(copy.contains("Noise Editor [noise]"));
    assertFalse(copy.contains("Source# # #snow"));
  }

  @Test
  void catalogOwnsValuesAndCollectionsAfterSourceMutation() {
    var metadata = AppMetadata.defaults();
    var catalog = new HelpCatalog(metadata);
    String original = catalog.topic("noise").copyText(catalog);
    metadata.controls.get("noise").label = "Mutated unpublished draft";
    metadata.controls.get("noise2D.algorithm").options.put("CONSTANT", "Changed");
    assertEquals(original, catalog.topic("noise").copyText(catalog));
    assertTrue(catalog.search("mutated unpublished").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> catalog.topics().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> catalog.topic("noise").related().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> catalog.topic("noise2D.algorithm").options().clear());
  }

  @Test
  void wordSearchReusesNormalizedProjectionAndNeverSelectsFallbackForAnEmptyMatch() {
    var catalog = new HelpCatalog(AppMetadata.defaults());
    var first = catalog.search("  NOISE\n  ALGORITHM ");
    long builds = catalog.builds();
    assertFalse(first.isEmpty());
    assertSame(first, catalog.search("noise algorithm"));
    assertEquals(builds, catalog.builds());
    assertTrue(catalog.search("no-such-phrase-123456").isEmpty());
    assertSame(catalog.topics(), catalog.search("\t \n"));
  }

  @Test
  void linksAndAllDesktopMenuRoutesResolveWithoutCyclesToSelfOrDuplicateKeys() {
    var catalog = new HelpCatalog(AppMetadata.defaults());
    assertEquals(
        catalog.topics().size(),
        new HashSet<>(catalog.topics().stream().map(HelpCatalog.Topic::id).toList()).size());
    for (var topic : catalog.topics()) {
      assertFalse(topic.related().contains(topic.id()));
      assertEquals(topic.related().size(), new HashSet<>(topic.related()).size());
      for (String key : topic.related()) assertNotNull(catalog.topic(key), key);
    }
    for (String action :
        List.of(
            "New",
            "Open",
            "Save",
            "SaveAs",
            "Exit",
            "Undo",
            "Redo",
            "Import",
            "Export",
            "Find",
            "FindReferences",
            "Validate",
            "Logs",
            "Profiler",
            "Debug & Profiling",
            "Settings",
            "User Settings",
            "Voxel Manager",
            "Themes",
            "Center Main Window",
            "Reset Dock Layout",
            "Project Settings",
            "Application Settings",
            "Help",
            "About",
            "Check for Updates"))
      assertNotNull(catalog.topic(HelpDefinitions.menuTopic(action)), action);
    assertEquals("help.find", HelpDefinitions.menuTopic("FindReferences"));
    assertEquals("help.updates", HelpDefinitions.menuTopic("Check for Updates"));
    assertThrows(IllegalArgumentException.class, () -> HelpDefinitions.menuTopic("made-up"));
  }
}
