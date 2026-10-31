package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldPreviewPaletteTest {
  private static final int RED = 0xff0000ff, GREEN = 0xff00ff00, BLUE = 0xffff0000;

  private static ProjectData fixture() {
    var p = new ProjectData();
    var a = new VoxelType();
    a.id = "a";
    a.color = new Color(255, 0, 0);
    var b = new VoxelType();
    b.id = "b";
    b.color = new Color(0, 255, 0);
    p.voxelTypes.addAll(List.of(a, b));
    return p;
  }

  private static int[] sourceColors() {
    return new int[] {0, RED, GREEN, 0xff2d1eeb, 0xff2d55eb, 0x12345678};
  }

  @Test
  void captureOwnsItsTypeOrderAndBothInputAndReturnedColorArrays() {
    var p = fixture();
    var a = p.voxelTypes.getFirst().uuid;
    var b = p.voxelTypes.getLast().uuid;
    var colors = sourceColors();
    var palette = WorldPreviewPalette.capture(p, colors);
    colors[1] = 0;
    p.voxelTypes.clear();
    assertEquals(List.of(a, b), palette.types());
    assertThrows(UnsupportedOperationException.class, () -> palette.types().clear());
    var copy = palette.colors();
    copy[1] = 0;
    assertEquals(RED, palette.colors()[1]);
    assertEquals(0x12345678, palette.colors()[5]);
  }

  @Test
  void reorderAndInsertionCannotReassignNormalOrRemovalGhostRuntimeIds() {
    var p = fixture();
    var palette = WorldPreviewPalette.capture(p, sourceColors());
    var a = p.voxelTypes.getFirst();
    var b = p.voxelTypes.getLast();
    a.color = new Color(40, 180, 240);
    b.color = new Color(20, 90, 150);
    var inserted = new VoxelType();
    inserted.id = "inserted";
    inserted.color = new Color(1, 2, 3);
    p.voxelTypes.clear();
    p.voxelTypes.addAll(List.of(inserted, b, a));
    var next = palette.refresh(p);
    var colors = next.colors();
    assertEquals(palette.types(), next.types());
    assertEquals(6, colors.length);
    assertEquals(0xfff0b428, colors[1]);
    assertEquals(0xff965a14, colors[2]);
    assertEquals(0xff503ceb, colors[3]);
    assertEquals(0xff321eeb, colors[4]);
    assertEquals(RED, palette.colors()[1]);
    assertEquals(0x12345678, colors[5]);
  }

  @Test
  void deletionAndReusedSemanticIdRetainLastKnownColorsUntilTheOriginalUuidReturns() {
    var p = fixture();
    var a = p.voxelTypes.getFirst();
    var palette = WorldPreviewPalette.capture(p, sourceColors());
    a.color = new Color(0, 0, 255);
    var blue = palette.refresh(p);
    p.voxelTypes.removeFirst();
    var replacement = new VoxelType();
    replacement.id = "a";
    replacement.color = new Color(255, 255, 0);
    p.voxelTypes.addFirst(replacement);
    var missing = blue.refresh(p);
    assertEquals(BLUE, missing.colors()[1]);
    assertEquals(a.uuid, missing.types().getFirst());
    a.color = new Color(10, 20, 30);
    p.voxelTypes.add(a);
    assertEquals(0xff1e140a, missing.refresh(p).colors()[1]);
    assertEquals(BLUE, blue.colors()[1]);
    assertEquals(BLUE, missing.colors()[1]);
  }

  @Test
  void foreignProjectCannotRecolorEvenWhenItsResourceUuidsAreReused() {
    var p = fixture();
    var palette = WorldPreviewPalette.capture(p, sourceColors());
    var other = ProjectIO.copy(p);
    other.project.uuid = UUID.randomUUID();
    other.voxelTypes.getFirst().color = new Color(0, 0, 255);
    assertSame(palette, palette.refresh(other));
    assertArrayEquals(sourceColors(), palette.colors());
  }

  @Test
  void lateAcceptanceUsesCurrentColorsAgainstTheCapturedOrderWithoutChangingGeometryIdentity() {
    var p = fixture();
    var snapshot = ProjectIO.copy(p);
    var palette = WorldPreviewPalette.capture(snapshot, sourceColors());
    var original = PreviewIdentity.of(p, 2, null);
    p.voxelTypes.getFirst().color = new Color(0, 0, 255);
    assertEquals(original, PreviewIdentity.of(p, 2, null));
    assertEquals(BLUE, palette.refresh(p).colors()[1]);
    Collections.reverse(p.voxelTypes);
    assertNotEquals(original, PreviewIdentity.of(p, 2, null));
    assertEquals(BLUE, palette.refresh(p).colors()[1]);
    assertEquals(RED, palette.colors()[1]);
  }

  @Test
  void emptySourcesKeepFallbackRowsAndMalformedRuntimeCoverageIsRejected() {
    var empty = new ProjectData();
    var colors = new int[] {0, RED, GREEN};
    var palette = WorldPreviewPalette.capture(empty, colors);
    assertArrayEquals(colors, palette.refresh(empty).colors());
    assertTrue(palette.types().isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> WorldPreviewPalette.capture(empty, new int[0]));
    assertThrows(
        IllegalArgumentException.class, () -> WorldPreviewPalette.capture(fixture(), new int[4]));
  }
}
