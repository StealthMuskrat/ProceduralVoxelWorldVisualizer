package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.BookmarkTransactions.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions for exact provisional bookmark/teleport coordinates. */
class CoordinateTextTest {
  @Test
  void invalidTextRetainsItsExactValueAndNeverReturnsEarlierCoordinates() {
    var text = new CoordinateText(Tab.WORLD, new Coordinates(1, 2, 3));
    for (var raw :
        new String[] {"", "-", "+", "2147483648", "-2147483649", "1.5", "1e3", " 7", "٧"}) {
      text.edit("X", raw);
      assertThrows(IllegalArgumentException.class, text::parse);
      assertEquals(raw, text.text("X"));
      assertEquals("2", text.text("Y"));
      assertEquals("3", text.text("Z"));
    }
  }

  @Test
  void allAxesAreValidatedTogetherAndSignedEndpointsRemainExact() {
    var text = new CoordinateText(Tab.NOISE_3D, new Coordinates(0, 0, 0));
    text.edit("X", "-2147483648");
    text.edit("Y", "+2147483647");
    text.edit("Z", "-");
    assertThrows(IllegalArgumentException.class, text::parse);
    text.edit("Z", "-17");
    assertEquals(new Coordinates(Integer.MIN_VALUE, Integer.MAX_VALUE, -17), text.parse());
  }

  @Test
  void twoDimensionalInputHasNoHiddenYAndRejectsWrongCoordinateShapes() {
    var text = new CoordinateText(Tab.NOISE_2D, new Coordinates(-3, null, -7));
    assertEquals(java.util.List.of("X", "Z"), text.axes());
    assertNull(text.parse().y());
    assertThrows(IllegalArgumentException.class, () -> text.edit("Y", "1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CoordinateText(Tab.WORLD, new Coordinates(0, null, 0)));
  }

  @Test
  void oversizedOrUnknownFieldEditsDoNotReplaceExistingInput() {
    var text = new CoordinateText(Tab.WORLD, new Coordinates(1, 2, 3));
    assertThrows(IllegalArgumentException.class, () -> text.edit("X", "1".repeat(129)));
    assertThrows(IllegalArgumentException.class, () -> text.edit("Q", "4"));
    assertEquals(new Coordinates(1, 2, 3), text.parse());
  }
}
