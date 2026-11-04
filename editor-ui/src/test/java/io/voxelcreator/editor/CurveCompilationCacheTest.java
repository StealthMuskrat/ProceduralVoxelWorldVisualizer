package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred compilation-reuse contracts; execution remains paused. */
class CurveCompilationCacheTest {
  private static Curve copy(Curve curve) {
    return ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(curve), Curve.class);
  }

  private static Curve three() {
    var c = new Curve();
    c.points.add(1, new Point(.5, .5));
    return c;
  }

  private static void bits(double expected, double actual) {
    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
  }

  @Test
  void unchangedValuesAndOwnedCopiesReuseAnImmutableCanonicalCurve() {
    var cache = new CurveCompilationCache();
    var c = new Curve();
    c.points.getFirst().y = -3;
    c.points.getLast().y = 5;
    var compiled = cache.get(c);
    for (int frame = 0; frame < 20; frame++) assertSame(compiled, cache.get(copy(c)));
    assertEquals(1, cache.builds());
    for (int i = 0; i <= 16; i++) bits(-3 + 8 * i / 16., compiled.sample(i / 16.));
    c.points.getLast().y = 9;
    bits(1, compiled.sample(.5));
    bits(3, cache.get(c).sample(.5));
  }

  @Test
  void everyAuthoredFieldIncludingValidationOnlyMetadataParticipatesInTheKey() {
    List<Consumer<Curve>> changes =
        List.of(
            c -> c.uuid = UUID.randomUUID(),
            c -> {
              c.min = -.5;
              c.points.getFirst().x = -.5;
            },
            c -> {
              c.max = 1.5;
              c.points.getLast().x = 1.5;
            },
            c -> c.displayMin = -.5,
            c -> c.displayMax = 2,
            c -> c.outsideDomain = "MIRROR",
            c -> c.points.get(1).uuid = UUID.randomUUID(),
            c -> c.points.get(1).x = .25,
            c -> c.points.get(1).y = .75,
            c -> c.points.get(1).inTangent = 2,
            c -> c.points.get(1).outTangent = 3,
            c -> c.points.get(1).handleMode = "FREE",
            c -> c.points.getFirst().segmentInterpolation = "BEZIER",
            c -> c.points.add(1, new Point(.25, .25)));
    for (var change : changes) {
      var cache = new CurveCompilationCache();
      var c = three();
      var original = cache.get(c);
      var edited = copy(c);
      change.accept(edited);
      var current = cache.get(edited);
      assertNotSame(original, current);
      assertSame(original, cache.get(c));
      assertSame(current, cache.get(copy(edited)));
      assertEquals(2, cache.builds());
    }
  }

  @Test
  void signedZeroOutputsAndTangentsCannotAliasDifferentAuthoredValues() {
    var cache = new CurveCompilationCache();
    var c = new Curve();
    c.points.getFirst().y = -0.0;
    var negative = cache.get(c);
    c.points.getFirst().y = 0.0;
    var positive = cache.get(c);
    assertNotSame(negative, positive);
    bits(-0.0, negative.sample(0));
    bits(0.0, positive.sample(0));
    c.points.getFirst().inTangent = -0.0;
    assertNotSame(positive, cache.get(c));
  }

  @Test
  void invalidChangesPreserveCanonicalDiagnosticsAndCannotHitAValidEntry() {
    List<Consumer<Curve>> invalid =
        List.of(
            c -> c.uuid = null,
            c -> c.points.get(1).uuid = c.uuid,
            c -> c.points.get(1).x = 0,
            c -> c.points.get(1).y = Double.NaN,
            c -> c.points.get(1).inTangent = Double.POSITIVE_INFINITY,
            c -> c.points.get(1).handleMode = "unknown",
            c -> {
              c.points.get(1).handleMode = "ALIGNED";
              c.points.get(1).inTangent = 2;
            },
            c -> c.points.getFirst().segmentInterpolation = "unknown",
            c -> c.outsideDomain = "unknown",
            c -> c.displayMax = c.displayMin,
            c -> c.max = c.min,
            c -> c.points.clear());
    for (var change : invalid) {
      var cache = new CurveCompilationCache();
      var c = three();
      var good = cache.get(c);
      var bad = copy(c);
      change.accept(bad);
      var expected = assertThrows(RuntimeException.class, () -> new ScalarCurve(bad));
      var actual = assertThrows(RuntimeException.class, () -> cache.get(bad));
      assertEquals(expected.getClass(), actual.getClass());
      assertEquals(expected.getMessage(), actual.getMessage());
      assertSame(good, cache.get(c));
      assertEquals(1, cache.entries());
    }
  }

  @Test
  void malformedShapesRetainConstructorValidationOrderAndDoNotEvictGoodData() {
    var cache = new CurveCompilationCache();
    var good = three();
    var retained = cache.get(good);
    for (int mode = 0; mode < 3; mode++) {
      var c = copy(good);
      if (mode == 0) {
        c.max = c.min;
        c.points.set(0, null);
      } else if (mode == 1) c.points = null;
      else c.points.get(1).segmentInterpolation = null;
      var expected = assertThrows(RuntimeException.class, () -> new ScalarCurve(c));
      var actual = assertThrows(RuntimeException.class, () -> cache.get(c));
      assertEquals(expected.getClass(), actual.getClass());
      assertEquals(expected.getMessage(), actual.getMessage());
      assertSame(retained, cache.get(good));
    }
  }

  @Test
  void invalidAttemptsAreNeverRememberedAndCorrectionCanReuseTheOriginalEntry() {
    var cache = new CurveCompilationCache();
    var c = new Curve();
    var valid = cache.get(c);
    c.points.getLast().x = 0;
    assertThrows(IllegalArgumentException.class, () -> cache.get(c));
    assertThrows(IllegalArgumentException.class, () -> cache.get(c));
    assertEquals(3, cache.builds());
    assertEquals(1, cache.entries());
    c.points.getLast().x = 1;
    assertSame(valid, cache.get(c));
  }

  @Test
  void lruEvictionIsBoundedAndPreviouslyReturnedCompiledValuesRemainOwned() {
    var cache = new CurveCompilationCache();
    var c = new Curve();
    var first = cache.get(c);
    for (int i = 1; i < CurveCompilationCache.MAX_ENTRIES; i++) {
      c.points.getLast().y = i + 1;
      cache.get(c);
    }
    c.points.getLast().y = 1;
    assertSame(first, cache.get(c));
    c.points.getLast().y = 99;
    cache.get(c);
    assertEquals(CurveCompilationCache.MAX_ENTRIES, cache.entries());
    c.points.getLast().y = 1;
    assertSame(first, cache.get(c));
    c.points.getLast().y = 2;
    cache.get(c);
    assertEquals(10, cache.builds());
    bits(.5, first.sample(.5));
  }

  @Test
  void estimatedByteBudgetEvictsLargeEntriesAndOversizedCurvesAreNotRetained() {
    var cache = new CurveCompilationCache();
    var c = new Curve();
    c.points.clear();
    for (int i = 0; i < 3000; i++) c.points.add(new Point(i / 2999., i / 2999.));
    for (int i = 0; i < 6; i++) {
      c.displayMin = -1 - i;
      cache.get(c);
    }
    assertTrue(cache.entries() < 6);
    assertTrue(cache.bytes() <= CurveCompilationCache.MAX_BYTES);
    var large = new Curve();
    large.points.clear();
    for (int i = 0; i < 9000; i++) large.points.add(new Point(i / 8999., i / 8999.));
    int entries = cache.entries();
    long bytes = cache.bytes();
    bits(1, cache.get(large).sample(1));
    assertEquals(entries, cache.entries());
    assertEquals(bytes, cache.bytes());
    cache.clear();
    assertEquals(0, cache.entries());
    assertEquals(0, cache.bytes());
  }

  @Test
  void acceptedEditsAndUndoRedoReuseTheAlreadyValidatedCanonicalDefinitions() {
    var session = new CurveEditSession(new Curve());
    var original = session.compiled();
    assertEquals(1, session.compilationBuilds());
    assertTrue(session.edit(c -> c.points.getLast().y = 2));
    var edited = session.compiled();
    bits(1, edited.sample(.5));
    assertEquals(2, session.compilationBuilds());
    session.undo();
    assertSame(original, session.compiled());
    bits(.5, session.compiled().sample(.5));
    session.redo();
    assertSame(edited, session.compiled());
    assertEquals(2, session.compilationBuilds());
    assertFalse(session.edit(c -> c.points.getLast().x = 0));
    assertSame(edited, session.compiled());
  }

  @Test
  void gestureCancellationRestoresBaselineCompilationWithoutCreatingLocalHistory() {
    var c = three();
    var session = new CurveEditSession(c);
    var base = session.compiled();
    var selected = Set.of(c.points.get(1).uuid);
    assertTrue(session.move(selected, 0, .25, false, false, .1, .1));
    assertNotSame(base, session.compiled());
    assertFalse(session.canUndo());
    session.cancelGesture();
    assertSame(base, session.compiled());
    assertFalse(session.canUndo());
    assertTrue(session.move(selected, 0, .25, false, false, .1, .1));
    session.endGesture();
    var edited = session.compiled();
    assertTrue(session.canUndo());
    session.undo();
    assertSame(base, session.compiled());
    session.redo();
    assertSame(edited, session.compiled());
  }

  @Test
  void insertionUsesTheCanonicalBaselineAndRawMutationCannotReuseStaleData() {
    var c = new Curve();
    c.points.getFirst().y = -3;
    c.points.getLast().y = 5;
    var session = new CurveEditSession(c);
    var before = session.compiled();
    var id = session.insert(.25, false, false, .1, .1);
    assertNotNull(id);
    bits(-1, CurveEditSession.point(session.curve(), id).y);
    assertEquals(2, session.compilationBuilds());
    var inserted = session.compiled();
    bits(1, inserted.sample(.5));
    session.curve().points.getLast().y = 9;
    var changed = session.compiled();
    assertNotSame(inserted, changed);
    bits(4, changed.sample(.625));
    bits(1, before.sample(.5));
    bits(1, inserted.sample(.5));
    session.curve().points.getLast().segmentInterpolation = "unknown";
    assertThrows(IllegalArgumentException.class, session::compiled);
  }

  @Test
  void allModesRetainCanonicalBitsAndNumericalFailuresAcrossCacheHits() {
    for (String mode : CurveModes.INTERPOLATIONS)
      for (String outside : CurveModes.OUTSIDE) {
        var c = three();
        c.outsideDomain = outside;
        c.points.forEach(
            p -> {
              p.segmentInterpolation = mode;
              p.handleMode = "FREE";
              p.inTangent = .25;
              p.outTangent = -.5;
            });
        var direct = new ScalarCurve(c);
        var cache = new CurveCompilationCache();
        var compiled = cache.get(c);
        assertSame(compiled, cache.get(copy(c)));
        for (int i = -8; i <= 16; i++) bits(direct.sample(i / 8.), compiled.sample(i / 8.));
      }
    var c = new Curve();
    c.outsideDomain = "EXTEND";
    c.points.getLast().y = 1e308;
    var cache = new CurveCompilationCache();
    var compiled = cache.get(c);
    assertThrows(ArithmeticException.class, () -> compiled.sample(4));
    assertSame(compiled, cache.get(c));
    bits(5e307, compiled.sample(.5));
  }
}
