package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProfileCaptureInputs.Field.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred exact next-capture publication definitions. */
class ProfileCaptureInputsTest {
  @Test
  void defaultsMatchTheEngineAndRequestsNeverRewriteSpelling() {
    var input = new ProfileCaptureInputs();
    assertEquals(Profiler.Options.defaults(), input.request(input.text()));
    input.edit(DURATION, "+0060000");
    input.edit(SAMPLES, "+00064");
    var options = input.request(input.text());
    assertEquals(60000, options.durationMillis());
    assertEquals(64, options.maxSamples());
    assertEquals("+0060000", input.text().get(DURATION));
    assertEquals("+00064", input.text().get(SAMPLES));
  }

  @Test
  void everyEngineBoundaryIsAcceptedAndNeighborsAreRejectedWithoutClamping() {
    for (var field : ProfileCaptureInputs.Field.values()) {
      var input = new ProfileCaptureInputs();
      for (int value : new int[] {field.minimum, field.maximum}) {
        input.edit(field, Integer.toString(value));
        assertNotNull(input.request(input.text()));
      }
      for (int value : new int[] {field.minimum - 1, field.maximum + 1}) {
        input.edit(field, Integer.toString(value));
        assertNull(input.feedback().options());
        assertEquals(Set.of(field), input.feedback().problems().keySet());
        assertEquals(Integer.toString(value), input.text().get(field));
        assertThrows(IllegalStateException.class, () -> input.request(input.text()));
      }
    }
  }

  @Test
  void unfinishedOverflowAndNonDecimalTextNeverReuseAValidOption() {
    var input = new ProfileCaptureInputs();
    var valid = input.request(input.text());
    for (String invalid :
        List.of("", "-", "+", "1e3", "100.0", " 100", "100 ", "2147483648", "NaN")) {
      input.edit(DURATION, invalid);
      assertNull(input.feedback().options());
      assertThrows(IllegalStateException.class, () -> input.request(input.text()));
      assertEquals(invalid, input.text().get(DURATION));
    }
    assertEquals(10000, valid.durationMillis());
    input.edit(DURATION, "100");
    assertEquals(100, input.request(input.text()).durationMillis());
  }

  @Test
  void completeExactDisplayedMapsAreRequiredBeforeStarting() {
    var input = new ProfileCaptureInputs();
    var missing = new EnumMap<>(input.text());
    missing.remove(STRIDE);
    assertThrows(IllegalStateException.class, () -> input.request(missing));
    for (var field : ProfileCaptureInputs.Field.values()) {
      var shown = new EnumMap<>(input.text());
      shown.put(field, "+0" + input.text().get(field));
      assertThrows(IllegalStateException.class, () -> input.request(shown));
    }
    assertNotNull(input.request(input.text()));
  }

  @Test
  void unchangedEditsReuseOwnedFeedbackWhileBoundedFailuresKeepTheOpeningText() {
    var input = new ProfileCaptureInputs();
    input.edit(STRIDE, "-");
    var feedback = input.feedback();
    input.edit(STRIDE, "-");
    assertSame(feedback, input.feedback());
    assertThrows(IllegalArgumentException.class, () -> input.edit(STRIDE, "1".repeat(129)));
    assertEquals("-", input.text().get(STRIDE));
    assertSame(feedback, input.feedback());
    assertThrows(UnsupportedOperationException.class, () -> input.text().clear());
    assertThrows(UnsupportedOperationException.class, () -> feedback.problems().clear());
    input.edit(STRIDE, "1");
    assertNotSame(feedback, input.feedback());
  }

  @Test
  void correctionPrioritizesDisplayedMismatchThenCanonicalFieldOrderWithoutRepair() {
    var input = new ProfileCaptureInputs();
    input.edit(DURATION, "-");
    input.edit(SAMPLES, "+");
    var shown = new EnumMap<>(input.text());
    shown.put(STRIDE, "1".repeat(129));
    assertEquals(STRIDE, input.firstProblem(shown));
    assertEquals(DURATION, input.firstProblem(input.text()));
    assertEquals("-", input.text().get(DURATION));
    assertEquals(129, shown.get(STRIDE).length());
  }

  @Test
  void editingNextCaptureNeverChangesAlreadyRunningOptionsOrItsRetainedMeasurements() {
    var input = new ProfileCaptureInputs();
    try (var profiler = new Profiler()) {
      var options = input.request(input.text());
      var capture = profiler.start(options);
      profiler
          .scope(1, null, null)
          .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 7, "queue");
      input.work(true);
      input.edit(SERIES, "1024");
      input.edit(DURATION, "-");
      assertThrows(IllegalStateException.class, () -> input.request(input.text()));
      assertSame(capture, profiler.current().orElseThrow());
      assertEquals(options, capture.snapshot().options());
      assertFalse(capture.snapshot().options().worldContributions());
      assertEquals(7, capture.snapshot().samples().getFirst().value());
      input.edit(DURATION, "60000");
      assertTrue(input.request(input.text()).worldContributions());
      assertEquals(1024, input.request(input.text()).maxSeries());
    }
  }
}
