package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CoordinateSliderTest {
  @Test
  void coordinateGuidanceExplainsExactTextRangesAndExplicitActions() {
    var catalog = new HelpCatalog(AppMetadata.defaults());
    var topic = catalog.topic("help.coordinates");
    assertTrue(catalog.search("coordinate slider").contains(topic));
    String copy = topic.copyText(catalog);
    assertTrue(copy.contains("256 blocks") && copy.contains("ties-to-even"));
    assertTrue(copy.contains(HelpDefinitions.COORDINATE_TOOLTIP));
    assertTrue(copy.contains("Trace, Teleport or Apply"));
    assertTrue(catalog.topic("bookmarks").related().contains(topic.id()));
  }

  @Test
  void neighborhoodsUseExactLongArithmeticAtBothSignedIntegerEdges() {
    assertEquals(new CoordinateSlider.Range(-256, 256), CoordinateSlider.neighborhood(0));
    assertEquals(
        new CoordinateSlider.Range(Integer.MIN_VALUE, Integer.MIN_VALUE + 256),
        CoordinateSlider.neighborhood(Integer.MIN_VALUE));
    assertEquals(
        new CoordinateSlider.Range(Integer.MAX_VALUE - 256, Integer.MAX_VALUE),
        CoordinateSlider.neighborhood(Integer.MAX_VALUE));
    assertEquals(
        new CoordinateSlider.Range(2_000_000_000 - 256, 2_000_000_000 + 256),
        CoordinateSlider.neighborhood(2_000_000_000));
  }

  @Test
  void idleFramesDoNotNormalizeAcceptedSignsZerosOrIntegerLimits() {
    for (String raw : List.of("+0007", "-0", "-2147483648", "2147483647")) {
      var slider = new CoordinateSlider();
      for (int frame = 0; frame < 100; frame++) {
        var view = slider.prepare(1, frame, raw, raw);
        assertTrue(view.enabled());
        assertEquals(Integer.parseInt(raw), view.value());
        assertTrue(slider.finish(false, false, view.value()).isEmpty());
        assertTrue(slider.finish(false, true, view.value()).isEmpty());
      }
    }
  }

  @Test
  void malformedAndDisagreeingRawTextCannotProposeFallbackCoordinates() {
    for (String raw : List.of("", "-", "+", "2147483648", "-2147483649", "1.5", "1e3", " 7", "٧")) {
      var slider = new CoordinateSlider();
      assertFalse(slider.prepare(1, 0, raw, raw).enabled());
      assertTrue(slider.finish(true, true, 17).isEmpty());
    }
    var slider = new CoordinateSlider();
    assertFalse(slider.prepare(1, 0, "7", "+7").enabled());
    assertTrue(slider.finish(false, true, 8).isEmpty());
  }

  @Test
  void heldRangeDoesNotWalkWithItsOwnAcceptedCoordinateEdits() {
    var slider = new CoordinateSlider();
    var first = slider.prepare(1, 0, "0", "0");
    assertEquals(Optional.of("200"), slider.finish(true, true, 200));
    assertEquals(first.range(), slider.prepare(1, 1, "200", "200").range());
    assertEquals(Optional.of("256"), slider.finish(true, true, 256));
    assertEquals(first.range(), slider.prepare(1, 2, "256", "256").range());
    assertTrue(slider.finish(false, false, 256).isEmpty());
    assertEquals(new CoordinateSlider.Range(0, 512), slider.prepare(1, 3, "256", "256").range());
  }

  @Test
  void externalInputReplacementRejectsTheHeldProposalThenAllowsFreshActivation() {
    var slider = new CoordinateSlider();
    slider.prepare(1, 0, "0", "0");
    slider.finish(true, false, 0);
    assertFalse(slider.prepare(1, 1, "99", "99").enabled());
    assertTrue(slider.finish(true, true, 20).isEmpty());
    assertFalse(slider.prepare(1, 2, "99", "99").enabled());
    assertTrue(slider.finish(false, true, 21).isEmpty());
    assertTrue(slider.prepare(1, 3, "99", "99").enabled());
    assertEquals(Optional.of("100"), slider.finish(true, true, 100));
  }

  @Test
  void newNativeIdentityOrAHiddenFrameRetiresPredecessorRangeOwnership() {
    for (boolean hidden : new boolean[] {false, true}) {
      var slider = new CoordinateSlider();
      slider.prepare(1, 0, "0", "0");
      slider.finish(true, false, 0);
      var view = slider.prepare(hidden ? 1 : 2, hidden ? 2 : 1, "1000", "1000");
      assertTrue(view.enabled());
      assertEquals(new CoordinateSlider.Range(744, 1256), view.range());
      assertEquals(Optional.of("1001"), slider.finish(true, true, 1001));
    }
  }

  @Test
  void nonfiniteOrOutOfNeighborhoodProposalsDoNotPublishOrNarrowByCasting() {
    var slider = new CoordinateSlider();
    for (double value :
        new double[] {
          Double.NaN,
          Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY,
          Double.MAX_VALUE,
          -257,
          257
        }) {
      slider.prepare(1, 0, "0", "0");
      assertTrue(slider.finish(false, true, value).isEmpty());
    }
    var view = slider.prepare(2, 1, "2147483647", "2147483647");
    assertEquals(Integer.MAX_VALUE, view.value());
    assertEquals(Optional.of("2147483646"), slider.finish(false, true, 2147483646.0));
  }

  @Test
  void roundingIsExplicitAndDoesNotNormalizeUnchangedIntegerText() {
    var slider = new CoordinateSlider();
    slider.prepare(1, 0, "+0007", "+0007");
    assertTrue(slider.finish(true, true, 7.25).isEmpty());
    slider.prepare(1, 1, "+0007", "+0007");
    assertEquals(Optional.of("8"), slider.finish(true, true, 7.5));
    slider.prepare(1, 2, "8", "8");
    assertEquals(Optional.of("6"), slider.finish(false, true, 6.5));
  }

  @Test
  void rawCoordinateOwnershipAdvancesOnlyAfterAnExplicitSliderProposalIsApplied() {
    var coordinates = new InspectorCoordinates();
    coordinates.edit("X", "-17");
    long version = coordinates.version();
    var slider = new CoordinateSlider();
    var view = slider.prepare(1, 0, coordinates.text("X"), coordinates.text("X"));
    slider.finish(false, false, view.value());
    assertEquals(version, coordinates.version());
    slider.prepare(1, 1, coordinates.text("X"), coordinates.text("X"));
    var proposal = slider.finish(true, true, -18).orElseThrow();
    assertEquals(version, coordinates.version());
    coordinates.edit("X", proposal);
    assertEquals(version + 1, coordinates.version());
    assertEquals(-18, coordinates.coordinate().x());
    assertEquals("0", coordinates.text("Y"));
    assertEquals("0", coordinates.text("Z"));
  }
}
