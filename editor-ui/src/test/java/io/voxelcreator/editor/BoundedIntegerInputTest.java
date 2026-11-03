package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class BoundedIntegerInputTest {
  private void range(long value, long min, long max, int radius) {
    var actual = ExactLongSlider.neighborhood(value, min, max, radius);
    var anchor = BigInteger.valueOf(value);
    assertEquals(
        anchor.subtract(BigInteger.valueOf(radius)).max(BigInteger.valueOf(min)),
        anchor.add(BigInteger.valueOf(actual.minimumOffset())));
    assertEquals(
        anchor.add(BigInteger.valueOf(radius)).min(BigInteger.valueOf(max)),
        anchor.add(BigInteger.valueOf(actual.maximumOffset())));
  }

  @Test
  void everyPerformanceFieldUsesItsOwnDeclaredDomainAndPresentationNeighborhood() {
    var values = PerformancePreferences.from(new UserConfig());
    for (var field : PerformancePreferences.Field.values()) {
      range(field.minimum(), field.minimum(), field.maximum(), field.sliderRadius());
      range(values.get(field), field.minimum(), field.maximum(), field.sliderRadius());
      range(field.maximum(), field.minimum(), field.maximum(), field.sliderRadius());
    }
  }

  @Test
  void captureLimitSlidersExposeTheCompleteExistingSmallDomains() {
    for (var field : ProfileCaptureInputs.Field.values())
      for (int value : new int[] {field.minimum, field.initial, field.maximum}) {
        var slider =
            ExactLongSlider.neighborhood(
                value, field.minimum, field.maximum, field.maximum - field.minimum);
        assertEquals(field.minimum, Math.addExact(slider.anchor(), slider.minimumOffset()));
        assertEquals(field.maximum, Math.addExact(slider.anchor(), slider.maximumOffset()));
      }
  }

  @Test
  void positiveLongBudgetsKeepAdjacentValuesBeyondDoublePrecision() {
    var slider = new ExactLongSlider();
    String raw = "9007199254740993";
    slider.prepare(1, 0, raw, raw, 1, Long.MAX_VALUE, 1_048_576);
    assertEquals(Optional.of("9007199254740994"), slider.finish(false, true, 1));
    range(Long.MAX_VALUE, 1, Long.MAX_VALUE, 1_048_576);
    range(1, 1, Long.MAX_VALUE, 1_048_576);
  }

  @Test
  void legalLongTextOutsideAFieldDomainCannotFallBackOrBeClampedByDrawing() {
    for (String raw : List.of("0", "63", "4097", "9223372036854775807")) {
      var slider = new ExactLongSlider();
      var view = slider.prepare(1, 0, raw, raw, 64, 4096, 4096);
      assertFalse(view.enabled());
      assertTrue(slider.finish(false, true, 100).isEmpty());
      assertEquals(Long.parseLong(raw), view.value());
    }
  }

  @Test
  void changingBoundsOrRadiusWhileHeldRejectsThePreviousPolicyProposal() {
    for (boolean radius : new boolean[] {false, true}) {
      var slider = new ExactLongSlider();
      slider.prepare(1, 0, "100", "100", 1, 1000, 256);
      slider.finish(true, false, 0);
      var changed = slider.prepare(1, 1, "100", "100", 1, radius ? 1000 : 500, radius ? 128 : 256);
      assertFalse(changed.enabled());
      assertTrue(slider.finish(false, true, 1).isEmpty());
      assertTrue(
          slider.prepare(1, 2, "100", "100", 1, radius ? 1000 : 500, radius ? 128 : 256).enabled());
      assertEquals(Optional.of("101"), slider.finish(false, true, 1));
    }
  }

  @Test
  void narrowedDomainKeepsRawTextDisabledUntilExplicitCorrection() {
    var slider = new ExactLongSlider();
    slider.prepare(1, 0, "100", "100", 1, 200, 200);
    slider.finish(true, false, 0);
    assertFalse(slider.prepare(1, 1, "100", "100", 1, 64, 64).enabled());
    assertTrue(slider.finish(false, true, -50).isEmpty());
    assertFalse(slider.prepare(1, 2, "100", "100", 1, 64, 64).enabled());
    slider.finish(false, false, 0);
    assertTrue(slider.prepare(1, 3, "64", "64", 1, 64, 64).enabled());
    assertEquals(Optional.of("63"), slider.finish(false, true, -1));
  }

  @Test
  void existingSeedOverloadRetainsItsFullSignedDomainAndDefaultRadius() {
    for (long value : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
      var old = new ExactLongSlider().prepare(1, 0, Long.toString(value), Long.toString(value));
      var bounded =
          new ExactLongSlider()
              .prepare(
                  1,
                  0,
                  Long.toString(value),
                  Long.toString(value),
                  Long.MIN_VALUE,
                  Long.MAX_VALUE,
                  256);
      assertEquals(old, bounded);
    }
  }

  @Test
  void invalidPoliciesRejectBeforeUseAndSingleValueDomainsNeverInventAnEdit() {
    assertThrows(IllegalArgumentException.class, () -> ExactLongSlider.neighborhood(1, 2, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> ExactLongSlider.neighborhood(1, 2, 3, 1));
    assertThrows(IllegalArgumentException.class, () -> ExactLongSlider.neighborhood(2, 1, 3, 0));
    var slider = new ExactLongSlider();
    assertThrows(IllegalArgumentException.class, () -> slider.prepare(1, 0, "1", "1", 2, 1, 1));
    var view = slider.prepare(1, 0, "+0007", "+0007", 7, 7, 1);
    assertTrue(view.enabled());
    assertEquals(0, view.range().minimumOffset());
    assertEquals(0, view.range().maximumOffset());
    assertTrue(slider.finish(false, true, 0).isEmpty());
    assertTrue(slider.finish(false, true, 1).isEmpty());
  }

  @Test
  void captureSliderEditsOnlyTheNextOptionsAndRetainsCompleteDisplayedValidation() {
    var input = new ProfileCaptureInputs();
    var before = input.feedback().options();
    var field = ProfileCaptureInputs.Field.DURATION;
    var slider = new ExactLongSlider();
    String raw = input.text().get(field);
    slider.prepare(1, 0, raw, raw, field.minimum, field.maximum, field.maximum - field.minimum);
    input.edit(field, slider.finish(false, true, 1000).orElseThrow());
    assertEquals(10000, before.durationMillis());
    assertEquals(11000, input.feedback().options().durationMillis());
    var displayed = new EnumMap<ProfileCaptureInputs.Field, String>(input.text());
    displayed.put(field, "10000");
    assertThrows(IllegalStateException.class, () -> input.request(displayed));
    input.edit(ProfileCaptureInputs.Field.STRIDE, "-");
    assertNull(input.feedback().options());
    assertEquals(10000, before.durationMillis());
  }

  @Test
  void coupledPerformanceErrorsRemainPrivateAndCanBeRepairedOneFieldAtATime() {
    var user = new UserConfig();
    user.performance.maxPreviewDimension = 96;
    user.visualization.previewSize = 32;
    var input = new PerformanceEditSession(user);
    input.edit(PerformancePreferences.Field.MAX_DIMENSION, "16");
    assertNull(input.feedback().value());
    assertTrue(
        input.feedback().problems().containsKey(PerformancePreferences.Field.DEFAULT_EXTENT));
    var slider = new ExactLongSlider();
    slider.prepare(1, 0, "32", "32", 2, 256, 256);
    input.edit(
        PerformancePreferences.Field.DEFAULT_EXTENT, slider.finish(false, true, -16).orElseThrow());
    assertNotNull(input.feedback().value());
    assertEquals(16, input.feedback().value().get(PerformancePreferences.Field.DEFAULT_EXTENT));
    assertEquals(96, user.performance.maxPreviewDimension);
    assertEquals(32, user.visualization.previewSize);
  }

  @Test
  void longBudgetInputRemainsExactAndDoesNotConsumeAnExternalActiveChange() {
    var user = new UserConfig();
    long before = user.performance.previewCellBudget;
    var input = new PerformanceEditSession(user);
    var field = PerformancePreferences.Field.PREVIEW_CELLS;
    input.edit(field, "9007199254740993");
    var slider = new ExactLongSlider();
    slider.prepare(
        1,
        0,
        input.text().get(field),
        input.text().get(field),
        field.minimum(),
        field.maximum(),
        field.sliderRadius());
    input.edit(field, slider.finish(false, true, 1).orElseThrow());
    assertEquals(9_007_199_254_740_994L, input.feedback().value().get(field));
    assertEquals(before, user.performance.previewCellBudget);
    user.performance.workers = user.performance.workers == 1 ? 2 : 1;
    assertFalse(input.current(user));
    assertEquals("9007199254740994", input.text().get(field));
  }

  @Test
  void runtimeLimitHelpExplainsPrivateInputsAndImmutableCaptureOwnership() {
    var catalog = new HelpCatalog(AppMetadata.defaults());
    var topic = catalog.topic("help.runtime-limits");
    assertTrue(catalog.search("performance capture limits").contains(topic));
    String text = topic.copyText(catalog);
    assertTrue(text.contains(HelpDefinitions.BOUNDED_INTEGER_TOOLTIP));
    assertTrue(text.contains("Start Capture") && text.contains("Apply Performance"));
    assertTrue(catalog.topic("profiler").related().contains(topic.id()));
  }
}
