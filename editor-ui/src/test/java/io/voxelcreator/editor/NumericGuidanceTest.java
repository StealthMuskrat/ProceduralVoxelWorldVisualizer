package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseAlgorithmRegistry;
import io.voxelcreator.core.NoiseAlgorithms;
import io.voxelcreator.model.ProjectIO;
import java.util.List;
import org.junit.jupiter.api.Test;

class NumericGuidanceTest {
  private static final String ID = "noise2D.algorithm.CONSTANT.value";

  @Test
  void sourceDefaultsUseCanonicalParameterHelpAndEngineRangesForBothDimensions() {
    var metadata = AppMetadata.defaults();
    var catalog = new HelpCatalog(metadata);
    for (int dimension : new int[] {2, 3})
      for (String algorithm : NoiseAlgorithms.KEYS)
        for (var parameter : NoiseAlgorithmRegistry.parameters(algorithm, dimension)) {
          String id = "noise" + dimension + "D.algorithm." + algorithm + "." + parameter.key();
          var tooltip = metadata.control(id).tooltip;
          assertTrue(tooltip.startsWith(parameter.help()));
          assertTrue(
              tooltip.contains(
                  "Engine range [" + parameter.minimum() + ", " + parameter.maximum()));
          assertEquals(tooltip, catalog.topic(id).tooltip());
        }
  }

  @Test
  void configuredTextAndCanonicalHelpIdentityStaySeparateFromRenamedLabels() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get(ID).label = "Renamed 100% ### value";
    metadata.controls.get(ID).tooltip = "Configured snow 雪\n100% ###";
    var guidance = NumericGuidance.from(metadata, ID, .125, -1, 1);
    assertEquals(ID, guidance.topic());
    assertTrue(guidance.text().startsWith("Configured snow 雪\n100% ###\nValue: 0.125"));
    assertTrue(guidance.text().endsWith("Right-click for Help."));
    assertTrue(guidance.text().contains("Suggested slider range [-1.0, 1.0]"));
  }

  @Test
  void exactValueIsNeverReconstructedFromItsFivePlaceReadout() {
    var metadata = AppMetadata.defaults();
    for (double value : new double[] {Math.nextUp(1.0), -0.0, Double.MIN_VALUE, 1e100, -1e-300}) {
      long bits = Double.doubleToRawLongBits(value);
      var guidance = NumericGuidance.from(metadata, ID, value, -1, 1);
      assertTrue(guidance.text().contains("Value: " + Double.toString(value) + "\n"));
      assertEquals(bits, Double.doubleToRawLongBits(value));
    }
  }

  @Test
  void unknownControlGetsHonestValueGuidanceWithoutAnUnavailableHelpAction() {
    var guidance = NumericGuidance.from(AppMetadata.defaults(), "unknown.control", .25, 0, 1);
    assertNull(guidance.topic());
    assertFalse(guidance.text().contains("Right-click"));
    assertTrue(guidance.text().startsWith("Value: 0.25"));
    assertThrows(IllegalStateException.class, () -> guidance.identity(1));
  }

  @Test
  void blankConfiguredTooltipsStillOfferTheKnownTopicAndExactValue() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get(ID).tooltip = " \n";
    var guidance = NumericGuidance.from(metadata, ID, Double.NaN, -1, 1);
    assertTrue(guidance.text().startsWith("Value: NaN"));
    assertEquals(ID, guidance.topic());
    assertTrue(guidance.text().contains("Right-click for Help."));
  }

  @Test
  void sliderAndTextPopupIdentitiesAreDistinctStableAndUseAllNativeIdBits() {
    var guidance = NumericGuidance.from(AppMetadata.defaults(), ID, 0, -1, 1);
    assertEquals(ID + ":numeric:4294967295", guidance.identity(-1));
    assertNotEquals(guidance.identity(-1), guidance.identity(Integer.MAX_VALUE));
    assertEquals(guidance.identity(17), guidance.identity(17));
    assertNotEquals(guidance.identity(17), guidance.identity(18));
    var other =
        NumericGuidance.from(
            AppMetadata.defaults(), "processing.REMAP.parameter.inputMin", 0, -1, 1);
    assertNotEquals(guidance.identity(17), other.identity(17));
  }

  @Test
  void guidanceSnapshotsDoNotMutateMetadataDefaultsOrFollowLaterPrivateEdits() {
    var metadata = AppMetadata.defaults();
    var before = ProjectIO.JSON.valueToTree(metadata);
    var opening = NumericGuidance.from(metadata, ID, .5, -1, 1);
    assertEquals(before, ProjectIO.JSON.valueToTree(metadata));
    metadata.controls.get(ID).tooltip = "Updated tooltip";
    var updated = NumericGuidance.from(metadata, ID, .5, -1, 1);
    assertFalse(opening.text().startsWith("Updated tooltip"));
    assertTrue(updated.text().startsWith("Updated tooltip"));
    assertFalse(AppMetadata.defaults().control(ID).tooltip.equals("Updated tooltip"));
    assertEquals(opening.identity(31), updated.identity(31));
  }

  @Test
  void missingPresentationOverridesUseDefaultTopicsForTransformWarpAndProcessingRows() {
    var metadata = AppMetadata.defaults();
    for (String id :
        List.of(
            ID,
            "transform2D.TRANSLATE.x",
            "processing.REMAP.parameter.inputMin",
            "processing.WARP_SAMPLE.parameter.amplitude")) {
      metadata.controls.remove(id);
      var guidance = NumericGuidance.from(metadata, id, .125, -1, 1);
      assertEquals(id, guidance.topic());
      assertTrue(guidance.text().startsWith(AppMetadata.defaults().control(id).tooltip));
    }
  }
}
