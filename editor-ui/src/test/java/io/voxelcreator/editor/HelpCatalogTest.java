package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HelpCatalogTest {
  @Test
  void commandHistoryHelpExplainsReadyCancellationAndDistinctDraftDecisions() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.command-history-recovery");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel/Escape controls run before accepting a ready background result"));
    assertTrue(text.contains("Resource Apply validates private Properties before the command"));
    assertTrue(text.contains("Undo/Redo never applies private Properties"));
    assertTrue(text.contains("without another confirmation"));
    assertTrue(catalog.search("prepared resource history").contains(topic));
    assertTrue(catalog.topic("help.project-history").related().contains(topic.id()));
    assertTrue(catalog.topic("help.resource-actions").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void childApplyHelpDistinguishesReadyCancellationMemberTransactionsAndPrivateCurves() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.child-apply-recovery");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("ready background result"));
    assertTrue(text.contains("Collapsing Curve Editor cancels pending Apply"));
    assertTrue(text.contains("one project Undo transaction"));
    assertTrue(text.contains("Curve Apply stages only its curve in private Properties"));
    assertTrue(catalog.search("membership prepared private").contains(topic));
    assertTrue(catalog.topic("help.membership-apply").related().contains(topic.id()));
    assertTrue(catalog.topic("help.curve-guidance").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void directApplyHelpExplainsReadyCancellationCollapseAndSeparatePrivateInput() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.direct-apply-recovery");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel/Escape run before accepting a ready background result"));
    assertTrue(text.contains("Collapsing either host retires pending work"));
    assertTrue(text.contains("every displayed raw seed/Y field"));
    assertTrue(text.contains("preserves separate private Properties edits"));
    assertTrue(catalog.search("properties prepared private").contains(topic));
    assertTrue(catalog.topic("help.properties-apply").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void guardedApplyHelpExplainsReadyCancellationAndOneOriginalAction() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.ready-navigation-apply");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel or Escape before accepting a ready result"));
    assertTrue(text.contains("does not require another Apply click"));
    assertTrue(text.contains("Only the work shown by that dialog may complete"));
    assertTrue(text.contains("later reveal failure cannot replay it"));
    assertTrue(catalog.search("navigation prepared cancel").contains(topic));
    assertTrue(catalog.topic("help.find").related().contains(topic.id()));
    assertTrue(catalog.topic("help.resource-session").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void transitionDraftHelpExplainsReadyCancellationAndSeparateSaveDecision() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.transition-draft-apply");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel Apply or Escape"));
    assertTrue(text.contains("one undoable project edit before the separate Save decision"));
    assertTrue(text.contains("Cancelling the later decision keeps that edit committed"));
    assertTrue(text.contains("retrying that Save only saves the project"));
    assertTrue(catalog.search("transition properties background").contains(topic));
    assertTrue(catalog.topic("save").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void ordinaryPreferenceSaveHelpExplainsCoalescingFailureRetryAndFinalExitSave() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.preference-save");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Repeated requests share pending work"));
    assertTrue(text.contains("saving coalesces to their latest values"));
    assertTrue(text.contains("unchanged failed save waits for explicit Save Preferences"));
    assertTrue(text.contains("finishes a final save before shutdown"));
    assertTrue(catalog.search("workspace background save").contains(topic));
    assertTrue(catalog.topic("help.preference-apply").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void themeAndBindingHelpExplainsCancellationPreviewOwnershipAndRecording() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.preference-apply");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel Apply or Escape"));
    assertTrue(text.contains("Starting Record retires an older binding Apply"));
    assertTrue(text.contains("Resume Preview is explicit"));
    assertTrue(text.contains("after a successful save it keeps saved colors"));
    assertTrue(catalog.search("keyboard background").contains(topic));
    assertTrue(catalog.topic("shortcuts").related().contains(topic.id()));
    assertTrue(catalog.topic("themes").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void structuralNoiseHelpExplainsAutomaticActionsResolutionRetryAndPrivateStaging() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.noise-edits");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("An initially valid action finishes"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("Retry preview"));
    assertTrue(text.contains("Apply Resolved Edit"));
    assertTrue(text.contains("raw seed text"));
    assertTrue(catalog.search("noise structural background").contains(topic));
    assertTrue(topic.related().contains("help.properties-apply"));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void importHelpExplainsFinalBackgroundPreparationAndExplicitImportRetry() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-import");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Final validation and Undo history prepare in the background"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("Import retry"));
    assertTrue(text.contains("Properties input stays separate"));
    assertTrue(catalog.search("import validation background").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void expressionHelpExplainsBackgroundFeedbackAndIndependentPrivateStaging() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.expression-feedback");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Retry checking"));
    assertTrue(text.contains("Use Text remains available"));
    assertTrue(text.contains("UTF-16"));
    assertTrue(text.contains("Properties Apply"));
    assertTrue(catalog.search("expression feedback background").contains(topic));
    assertTrue(topic.related().contains("help.properties-apply"));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void membershipHelpExplainsBackgroundCancellationAndPrivateFieldRetention() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.membership-apply");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("Changing checkboxes cancels older work"));
    assertTrue(text.contains("its private ID and raw input stay private"));
    assertTrue(catalog.search("membership background apply").contains(topic));
    assertTrue(catalog.topic("help.voxel-identities").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void resourceActionHelpExplainsStagedBackgroundValidationAndExplicitRetry() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-actions");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Retry Action"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("a resource cannot hide its invalid private draft"));
    assertTrue(catalog.search("resource actions background").contains(topic));
    assertTrue(catalog.topic("help.resource-session").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void browserHelpExplainsBackgroundSelectionCancelAndHistorySeparation() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-session");
    var text = topic.copyText(catalog);
    assertTrue(
        text.contains("Selection Apply prepares validation and Undo history in the background"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("Selection remains outside Undo history"));
    assertTrue(catalog.search("selection background apply").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void navigationHelpExplainsBackgroundApplyCancelRetryAndPostApplySourceChecks() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.find");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Undo history in the background"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("edit remains committed without following that old link"));
    assertTrue(catalog.search("navigation background apply").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void projectSettingsHelpExplainsBackgroundInputOwnershipAndSeparateProperties() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.project-settings");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Undo delta in the background"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("Separate private Properties edits"));
    assertTrue(text.contains("Apply does not save the project"));
    assertTrue(catalog.search("project settings background").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void historyHelpRoutesUndoRedoAndExplainsBackgroundDiscardCancellationAndRetry() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.project-history");
    var text = topic.copyText(catalog);
    assertEquals(topic.id(), HelpDefinitions.menuTopic("Undo"));
    assertEquals(topic.id(), HelpDefinitions.menuTopic("Redo"));
    assertTrue(text.contains("Discard takes effect only when"));
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(catalog.search("history undo background").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void propertiesApplyHelpExplainsBackgroundValidationCancellationAndPrivatePublication() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.properties-apply");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel Apply or Escape"));
    assertTrue(text.contains("Undo delta in the background"));
    assertTrue(text.contains("does not save the project"));
    assertTrue(catalog.search("properties apply background").contains(topic));
    assertTrue(catalog.topic("noise").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void exportHelpExplainsBackgroundCatalogSelectionReloadAndExplicitPreviewRetry() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-export");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("resource catalog"));
    assertTrue(text.contains("Rapid selections supersede"));
    assertTrue(text.contains("Retry preview on the Reload action"));
    assertTrue(text.contains("last Included plan remains visible"));
    assertTrue(catalog.search("export catalog preview").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void importHelpExplainsBackgroundChoiceRebuildAndExplicitRetryReview() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-import");
    var text = topic.copyText(catalog);
    assertTrue(text.contains("Changed choices and Reload rebuild"));
    assertTrue(text.contains("Retry preview"));
    assertTrue(text.contains("keeping COPY identities"));
    assertTrue(catalog.search("import retry preview").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void curveGuidanceExplainsGuardedLinearExtensionWithoutChangingModeOrMetadataAuthority() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.curve-guidance");
    String text = topic.copyText(catalog);
    assertTrue(text.contains("Linear and Extend"));
    assertTrue(text.contains("cubic coefficient validation unchanged"));
    assertTrue(catalog.search("linear extension arithmetic").contains(topic));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void resourceExportHelpExplainsReviewStagingCancelAndActiveProjectProtection() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-export");
    assertEquals(topic.id(), HelpDefinitions.menuTopic("Export"));
    assertTrue(catalog.search("export background included").contains(topic));
    String text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("explicit retry"));
    assertTrue(text.contains("active project file"));
    assertTrue(text.contains("Undo history"));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void resourceImportHelpExplainsBackgroundCancelRetryAndExplicitReviewWithoutChangingMetadata() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.resource-import");
    assertEquals(topic.id(), HelpDefinitions.menuTopic("Import"));
    assertTrue(catalog.search("import background conflict").contains(topic));
    String text = topic.copyText(catalog);
    assertTrue(text.contains("Cancel or Escape"));
    assertTrue(text.contains("explicit retry"));
    assertTrue(text.contains("one undoable transaction"));
    assertTrue(text.contains("Private Properties"));
    assertTrue(catalog.topic("archives").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void hierarchyRecoveryGuidanceIsSearchableAndCompleteWithoutChangingMetadata() {
    var metadata = AppMetadata.defaults();
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata);
    var catalog = new HelpCatalog(metadata);
    var topic = catalog.topic("help.folder-hierarchy");
    assertTrue(catalog.search("folder ancestry cycle").contains(topic));
    String text = topic.copyText(catalog);
    assertTrue(text.contains("distinct direct child"));
    assertTrue(text.contains("Failed Open preserves the current project"));
    assertNotNull(catalog.topic("help.project-open"));
    assertTrue(catalog.topic("help.project-open").related().contains(topic.id()));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(metadata));
  }

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
    assertEquals("help.project-open", HelpDefinitions.menuTopic("Open"));
    assertEquals("help.project-save", HelpDefinitions.menuTopic("Save"));
    assertEquals("help.project-save", HelpDefinitions.menuTopic("SaveAs"));
    assertEquals("help.find", HelpDefinitions.menuTopic("FindReferences"));
    assertEquals("help.validation", HelpDefinitions.menuTopic("Validate"));
    assertTrue(
        catalog.search("validation background").stream()
            .anyMatch(topic -> topic.id().equals("help.validation")));
    assertTrue(catalog.topic("help.validation").copyText(catalog).contains("Cancel Validation"));
    assertEquals("help.updates", HelpDefinitions.menuTopic("Check for Updates"));
    assertThrows(IllegalArgumentException.class, () -> HelpDefinitions.menuTopic("made-up"));
  }
}
