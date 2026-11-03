package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExactLongSliderTest {
  @Test
  void neighborhoodsMatchIndependentBigIntegerBoundsAtBothLimitsAndBeyondDoublePrecision() {
    var minimum = BigInteger.valueOf(Long.MIN_VALUE);
    var maximum = BigInteger.valueOf(Long.MAX_VALUE);
    for (long value :
        new long[] {
          Long.MIN_VALUE,
          Long.MIN_VALUE + 1,
          Long.MIN_VALUE + 255,
          Long.MIN_VALUE + 256,
          -9_007_199_254_740_993L,
          -1,
          0,
          1,
          9_007_199_254_740_993L,
          Long.MAX_VALUE - 256,
          Long.MAX_VALUE - 255,
          Long.MAX_VALUE - 1,
          Long.MAX_VALUE
        }) {
      var range = ExactLongSlider.neighborhood(value);
      var anchor = BigInteger.valueOf(value);
      assertEquals(
          anchor.subtract(BigInteger.valueOf(256)).max(minimum),
          anchor.add(BigInteger.valueOf(range.minimumOffset())));
      assertEquals(
          anchor.add(BigInteger.valueOf(256)).min(maximum),
          anchor.add(BigInteger.valueOf(range.maximumOffset())));
      assertEquals(value, range.anchor());
    }
  }

  @Test
  void singleStepsBeyondTwoToTheFiftyThirdRemainExactInBothDirections() {
    for (long value : new long[] {9_007_199_254_740_993L, -9_007_199_254_740_993L})
      for (int offset : new int[] {-1, 1}) {
        var slider = new ExactLongSlider();
        var view = slider.prepare(1, 0, Long.toString(value), Long.toString(value));
        assertEquals(0, view.offset());
        assertEquals(
            Optional.of(BigInteger.valueOf(value).add(BigInteger.valueOf(offset)).toString()),
            slider.finish(false, true, offset));
      }
  }

  @Test
  void endpointSlidersRejectOverflowAndAllowTheirExactAdjacentLong() {
    var min = new ExactLongSlider();
    min.prepare(1, 0, Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE));
    assertTrue(min.finish(true, true, -1).isEmpty());
    assertEquals(Optional.of("-9223372036854775807"), min.finish(false, true, 1));
    var max = new ExactLongSlider();
    max.prepare(1, 0, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE));
    assertTrue(max.finish(true, true, 1).isEmpty());
    assertEquals(Optional.of("9223372036854775806"), max.finish(false, true, -1));
  }

  @Test
  void heldRangeUsesOneExactAnchorUntilTheInteractionEnds() {
    long anchor = 9_007_199_254_740_993L;
    var slider = new ExactLongSlider();
    var range = slider.prepare(1, 0, Long.toString(anchor), Long.toString(anchor)).range();
    String first = slider.finish(true, true, 200).orElseThrow();
    var next = slider.prepare(1, 1, first, first);
    assertEquals(range, next.range());
    assertEquals(200, next.offset());
    String last = slider.finish(false, true, 256).orElseThrow();
    assertEquals(anchor + 256, Long.parseLong(last));
    assertEquals(anchor + 256, slider.prepare(1, 2, last, last).range().anchor());
  }

  @Test
  void malformedOrDisagreeingTextNeverUsesThePreviousValueAsAFallback() {
    for (String raw :
        List.of(
            "", "-", "+", "9223372036854775808", "-9223372036854775809", "1e3", "1.5", " 7", "٧")) {
      var slider = new ExactLongSlider();
      assertFalse(slider.prepare(1, 0, raw, raw).enabled());
      assertTrue(slider.finish(false, true, 1).isEmpty());
    }
    var slider = new ExactLongSlider();
    assertFalse(slider.prepare(1, 0, "7", "+7").enabled());
    assertTrue(slider.finish(false, true, 1).isEmpty());
  }

  @Test
  void idleAndUnchangedOffsetsDoNotCanonicalizeValidRawSignsOrZeros() {
    for (String raw : List.of("+0007", "-0", "-9223372036854775808", "9223372036854775807")) {
      var slider = new ExactLongSlider();
      for (int frame = 0; frame < 100; frame++) {
        var view = slider.prepare(1, frame, raw, raw);
        assertTrue(view.enabled());
        assertEquals(Long.parseLong(raw), view.value());
        assertTrue(slider.finish(false, true, 0).isEmpty());
      }
    }
  }

  @Test
  void replacedRawInputRetiresHeldProposalsWithoutOverflowingAnchorSubtraction() {
    var slider = new ExactLongSlider();
    slider.prepare(1, 0, Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE));
    slider.finish(true, false, 0);
    assertFalse(
        slider
            .prepare(1, 1, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE))
            .enabled());
    assertTrue(slider.finish(true, true, 1).isEmpty());
    assertFalse(
        slider
            .prepare(1, 2, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE))
            .enabled());
    slider.finish(false, false, 0);
    assertTrue(
        slider
            .prepare(1, 3, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE))
            .enabled());
    assertEquals(Optional.of("9223372036854775806"), slider.finish(true, true, -1));
  }

  @Test
  void hiddenFramesOrNewWidgetOwnersCannotRetainAnEarlierLongAnchor() {
    for (boolean hidden : new boolean[] {false, true}) {
      var slider = new ExactLongSlider();
      slider.prepare(1, 0, "0", "0");
      slider.finish(true, false, 0);
      var view =
          slider.prepare(
              hidden ? 1 : 2,
              hidden ? 2 : 1,
              Long.toString(Long.MAX_VALUE),
              Long.toString(Long.MAX_VALUE));
      assertTrue(view.enabled());
      assertEquals(Long.MAX_VALUE, view.range().anchor());
    }
  }

  @Test
  void roundingAndFiniteOffsetAdmissionDoNotRoundTheLongAnchor() {
    var slider = new ExactLongSlider();
    String raw = "9007199254740993";
    for (double value :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, -257, 257}) {
      slider.prepare(1, 0, raw, raw);
      assertTrue(slider.finish(false, true, value).isEmpty());
    }
    slider.prepare(1, 1, raw, raw);
    assertEquals(Optional.of("9007199254740995"), slider.finish(false, true, 2.5));
    slider.prepare(1, 2, raw, raw);
    assertEquals(Optional.of("9007199254740997"), slider.finish(false, true, 3.5));
  }

  @Test
  void projectSliderProposalsStayInThePrivateSessionUntilExplicitApply() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "9007199254740993");
      var slider = new ExactLongSlider();
      slider.prepare(1, 0, session.text("seed"), session.text("seed"));
      session.edit("seed", slider.finish(false, true, 1).orElseThrow());
      assertEquals("9007199254740994", session.text("seed"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertTrue(session.apply(state));
      assertEquals(9_007_199_254_740_994L, state.project().project.seed);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void creationSliderEditsUseTheExistingPrivateExactLongLedger() {
    var source = AppMetadata.defaults();
    var draft = AppMetadata.copy(source);
    var input = new CreationValueInputs(draft);
    input.override("noise.seedOffset", true);
    input.edit("noise.seedOffset", 0, Long.toString(Long.MAX_VALUE));
    var slider = new ExactLongSlider();
    String raw = input.text("noise.seedOffset").getFirst();
    slider.prepare(1, 0, raw, raw);
    input.edit("noise.seedOffset", 0, slider.finish(false, true, -1).orElseThrow());
    assertEquals(Long.MAX_VALUE - 1, draft.creationDefaults.get("noise.seedOffset").longValue());
    assertFalse(source.creationDefaults.containsKey("noise.seedOffset"));
    assertTrue(input.error("noise.seedOffset").isEmpty());
  }

  @Test
  void helpExplainsExactLongOffsetsAndExistingPublicationBoundaries() {
    var catalog = new HelpCatalog(AppMetadata.defaults());
    var topic = catalog.topic("help.long-inputs");
    assertTrue(catalog.search("seed slider").contains(topic));
    String copy = topic.copyText(catalog);
    assertTrue(copy.contains(HelpDefinitions.LONG_SLIDER_TOOLTIP));
    assertTrue(copy.contains("offset") && copy.contains("64-bit") && copy.contains("Apply"));
  }
}
