package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred contracts for raw Inspector text, no fallback and request ownership. */
class InspectorCoordinatesTest {
  private Map<String, String> displayed(InspectorCoordinates input) {
    var result = new LinkedHashMap<String, String>();
    input.axes().forEach(axis -> result.put(axis, input.text(axis)));
    return result;
  }

  @Test
  void incompleteOverflowAndNonDecimalInputsCannotReturnTheOldCell() {
    var input = new InspectorCoordinates();
    input.set(new BlockPos(3, 4, 5));
    for (var raw : List.of("", "-", "+", "2147483648", "-2147483649", "1.5", "1e3", " 7", "٧")) {
      input.edit("X", raw);
      assertEquals(raw, input.text("X"));
      assertEquals("4", input.text("Y"));
      assertEquals("5", input.text("Z"));
      assertFalse(input.problem().isEmpty());
      assertTrue(input.differs(new BlockPos(3, 4, 5)));
      assertThrows(IllegalArgumentException.class, input::coordinate);
      assertThrows(IllegalArgumentException.class, () -> input.coordinate(displayed(input)));
    }
    input.edit("X", "-7");
    assertTrue(input.problem().isEmpty());
    assertEquals(new BlockPos(-7, 4, 5), input.coordinate());
  }

  @Test
  void signedIntegerEndpointsAreExactAndEveryAxisMustPassBeforeActions() {
    var input = new InspectorCoordinates();
    input.edit("X", "-2147483648");
    input.edit("Y", "+2147483647");
    input.edit("Z", "+");
    assertThrows(IllegalArgumentException.class, input::coordinate);
    assertTrue(input.problem().startsWith("Z "));
    input.edit("Z", "-17");
    assertEquals(
        new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -17),
        input.coordinate(displayed(input)));
    assertFalse(input.differs(new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -17)));
  }

  @Test
  void displayMismatchCannotFallBackAfterAnOversizedRejectedEdit() {
    var input = new InspectorCoordinates();
    var before = displayed(input);
    long version = input.version();
    var excessive = "1".repeat(CoordinateText.MAX_CHARACTERS + 1);
    assertThrows(IllegalArgumentException.class, () -> input.edit("X", excessive));
    assertEquals(before, displayed(input));
    assertEquals(version, input.version());
    var nativeBuffer = displayed(input);
    nativeBuffer.put("X", excessive);
    assertThrows(IllegalStateException.class, () -> input.coordinate(nativeBuffer));
    nativeBuffer.put("X", "0");
    assertEquals(new BlockPos(0, 0, 0), input.coordinate(nativeBuffer));
  }

  @Test
  void coordinateEditsInvalidateBothKindsOfOwnedRequestEvenIfTheParsedCellIsEqual() {
    for (boolean viewBound : new boolean[] {true, false}) {
      var input = new InspectorCoordinates();
      var gate = new InspectorRequests();
      var request = gate.begin(9, viewBound);
      long version = input.version();
      input.edit("X", "+0");
      assertEquals(new BlockPos(0, 0, 0), input.coordinate());
      assertTrue(gate.current(request, 9));
      assertNotEquals(version, input.version());
      assertFalse(gate.current(request, 9) && version == input.version());
      long latest = input.version();
      input.edit("X", "+0");
      assertEquals(latest, input.version());
    }
  }

  @Test
  void explicitRestorationReplacesAllInvalidTextAndPublishesNoImplicitRequest() {
    var input = new InspectorCoordinates();
    var gate = new InspectorRequests();
    var request = gate.begin(3, false);
    input.edit("X", "-");
    input.edit("Y", "+");
    input.edit("Z", "1e2");
    long version = input.version();
    var point = new BlockPos(-3, 2, -7);
    input.set(point);
    assertEquals(Map.of("X", "-3", "Y", "2", "Z", "-7"), displayed(input));
    assertTrue(input.problem().isEmpty());
    assertEquals(point, input.coordinate());
    assertNotEquals(version, input.version());
    assertTrue(gate.current(request, 3));
  }

  @Test
  void unknownOrMissingFieldsRejectWithoutReplacingOwnedInput() {
    var input = new InspectorCoordinates();
    assertEquals(List.of("X", "Y", "Z"), input.axes());
    assertThrows(UnsupportedOperationException.class, () -> input.axes().clear());
    assertThrows(IllegalArgumentException.class, () -> input.edit("Q", "1"));
    assertThrows(IllegalStateException.class, () -> input.coordinate(Map.of("X", "0", "Y", "0")));
    var extra = displayed(input);
    extra.put("Q", "0");
    assertThrows(IllegalStateException.class, () -> input.coordinate(extra));
    assertEquals(new BlockPos(0, 0, 0), input.coordinate());
    assertEquals(0, input.version());
  }

  @Test
  void problemNavigationUsesCanonicalAxisOrderAndRetainsEveryRawValue() {
    var input = new InspectorCoordinates();
    input.edit("Z", "-");
    input.edit("Y", "2147483648");
    input.edit("X", "+");
    long version = input.version();
    assertEquals("X", input.problemAxis(displayed(input)));
    assertEquals(version, input.version());
    assertEquals("+", input.text("X"));
    input.edit("X", "-2147483648");
    assertEquals("Y", input.problemAxis(displayed(input)));
    input.edit("Y", "+2147483647");
    assertEquals("Z", input.problemAxis(displayed(input)));
    input.edit("Z", "+0");
    assertNull(input.problemAxis(displayed(input)));
    assertEquals(new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, 0), input.coordinate());
  }

  @Test
  void displayedDisagreementPrecedesSyntaxAndDoesNotAdoptNativeText() {
    var input = new InspectorCoordinates();
    input.edit("X", "-");
    var raw = displayed(input);
    raw.put("Z", "7");
    long version = input.version();
    assertEquals("Z", input.problemAxis(raw));
    assertEquals("0", input.text("Z"));
    assertEquals("-", input.text("X"));
    assertEquals(version, input.version());
    raw.put("Z", "0");
    assertEquals("X", input.problemAxis(raw));
    assertThrows(IllegalArgumentException.class, () -> input.coordinate(raw));
  }

  @Test
  void firstDisplayMismatchUsesCanonicalOrderRegardlessOfMapOrder() {
    var input = new InspectorCoordinates();
    var raw = new LinkedHashMap<String, String>();
    raw.put("Z", "3");
    raw.put("Y", "2");
    raw.put("X", "1");
    assertEquals("X", input.problemAxis(raw));
    raw.put("X", "0");
    assertEquals("Y", input.problemAxis(raw));
    raw.put("Y", "0");
    assertEquals("Z", input.problemAxis(raw));
    assertEquals(new BlockPos(0, 0, 0), input.coordinate());
  }

  @Test
  void navigationRejectsMissingExtraAndNullBuffersWithoutChangingAcceptedInput() {
    var input = new InspectorCoordinates();
    assertThrows(IllegalStateException.class, () -> input.problemAxis(Map.of("X", "0")));
    var raw = displayed(input);
    raw.put("Q", "0");
    assertThrows(IllegalStateException.class, () -> input.problemAxis(raw));
    raw.remove("Q");
    raw.put("Y", null);
    assertEquals("Y", input.problemAxis(raw));
    assertEquals(new BlockPos(0, 0, 0), input.coordinate());
    assertEquals(0, input.version());
  }

  @Test
  void everyAxisUsesTheSameStrictDecimalPolicyForFocusAndParsing() {
    for (var axis : List.of("X", "Y", "Z"))
      for (var raw : List.of("", "+", "-", " 7", "7 ", "1.0", "1e3", "٧", "2147483648")) {
        var input = new InspectorCoordinates();
        input.edit(axis, raw);
        assertEquals(axis, input.problemAxis(displayed(input)));
        assertTrue(input.problem().startsWith(axis + " "));
        assertThrows(IllegalArgumentException.class, input::coordinate);
      }
  }
}
