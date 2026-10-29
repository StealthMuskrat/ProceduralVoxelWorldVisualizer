package io.voxelcreator.editor;

import static io.voxelcreator.editor.WindowPlacement.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred screen-coordinate vectors, frame extents and strict preference compatibility. */
class WindowPlacementTest {
  @TempDir Path directory;
  private static final Insets FRAME = new Insets(8, 30, 8, 8);

  @Test
  void historicalUnpositionedWindowCentersOnThePrimaryWorkAreaIncludingItsOrigin() {
    var workspace = workspace(null, null, 800, 600);
    var plan =
        WindowPlacement.restore(
            workspace, List.of(new WorkArea(100, 40, 1920, 1080)), FRAME, false);
    assertEquals(new Rect(660, 291, 800, 600), plan.normal());
    assertFalse(plan.maximized());
    assertNull(workspace.x);
    assertEquals(800, workspace.width);
  }

  @Test
  void negativeSecondaryCoordinatesArePreservedWithoutPrimaryMonitorClamping() {
    var workspace = workspace(-1800, -600, 800, 600);
    var areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(-1920, -900, 1920, 1080));
    assertEquals(
        new Rect(-1800, -600, 800, 600),
        WindowPlacement.restore(workspace, areas, FRAME, false).normal());
  }

  @Test
  void largestOverlapWinsAndEqualOverlapKeepsPrimaryOrder() {
    var workspace = workspace(1800, 100, 800, 600);
    var areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(1920, 0, 1920, 1080));
    assertEquals(1928, WindowPlacement.restore(workspace, areas, FRAME, false).normal().x());
    workspace.x = 1500;
    workspace.width = 840;
    assertEquals(1080, WindowPlacement.restore(workspace, areas, Insets.NONE, false).normal().x());
  }

  @Test
  void missingMonitorUsesNearestRectangleGapWithStableTies() {
    var workspace = workspace(-1500, 100, 800, 600);
    var areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(-3840, 0, 1920, 1080));
    assertEquals(-2728, WindowPlacement.restore(workspace, areas, FRAME, false).normal().x());
    workspace.x = -10000;
    assertEquals(-3832, WindowPlacement.restore(workspace, areas, FRAME, false).normal().x());
  }

  @Test
  void oversizedGeometryFitsClientPlusCurrentNativeFrameIntoTheWorkArea() {
    var workspace = workspace(5000, -5000, Integer.MAX_VALUE, Integer.MAX_VALUE);
    var area = new WorkArea(100, 40, 1280, 720);
    assertEquals(
        new Rect(108, 70, 1264, 682),
        WindowPlacement.restore(workspace, List.of(area), FRAME, false).normal());
    assertEquals(Integer.MAX_VALUE, workspace.width);
  }

  @Test
  void tinyOrMissingWorkAreasKeepMinimumSizeAndReachableTopLeftWithoutIntegerOverflow() {
    var workspace = workspace(null, null, 800, 600);
    assertEquals(
        new Rect(18, 50, 400, 300),
        WindowPlacement.restore(workspace, List.of(new WorkArea(10, 20, 200, 100)), FRAME, false)
            .normal());
    assertEquals(
        new Rect(320, 150, 800, 600),
        WindowPlacement.restore(workspace, List.of(), Insets.NONE, false).normal());
    workspace.x = Integer.MAX_VALUE;
    workspace.y = Integer.MIN_VALUE;
    workspace.width = Integer.MAX_VALUE;
    workspace.height = Integer.MAX_VALUE;
    var plan =
        WindowPlacement.restore(
            workspace,
            List.of(
                new WorkArea(
                    Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)),
            Insets.NONE,
            false);
    assertEquals(
        new Rect(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
        plan.normal());
  }

  @Test
  void centerRestoresNormalModeAndFitsPrimaryWhilePreservingMaximizedStartupPlans() {
    var workspace = workspace(-1800, -600, 800, 600);
    workspace.maximized = true;
    var areas = List.of(new WorkArea(100, 40, 1920, 1080), new WorkArea(-1920, -900, 1920, 1080));
    assertTrue(WindowPlacement.restore(workspace, areas, FRAME, false).maximized());
    var centered = WindowPlacement.restore(workspace, areas, FRAME, true);
    assertFalse(centered.maximized());
    assertEquals(new Rect(660, 291, 800, 600), centered.normal());
  }

  @Test
  void topologyRecoveryRequiresAUsableTitleBarSegmentRatherThanOneInvisiblePixel() {
    var areas = List.of(new WorkArea(0, 40, 1920, 1000));
    assertTrue(WindowPlacement.reachable(new Rect(-600, 70, 800, 600), FRAME, areas));
    assertFalse(WindowPlacement.reachable(new Rect(-700, 70, 800, 600), FRAME, areas));
    assertFalse(WindowPlacement.reachable(new Rect(100, 40, 800, 600), FRAME, areas));
    assertTrue(WindowPlacement.reachable(new Rect(100, 70, 800, 600), FRAME, areas));
  }

  @Test
  void strictPositionShapesAndHistoricalDefaultsRoundTripWithoutReadSideWrites() throws Exception {
    Path file = directory.resolve("user.json");
    Files.writeString(file, "{\"formatVersion\":1}");
    byte[] historical = Files.readAllBytes(file);
    var restored = UserConfig.read(file);
    assertNull(restored.workspace.x);
    assertNull(restored.workspace.y);
    assertFalse(restored.workspace.maximized);
    assertArrayEquals(historical, Files.readAllBytes(file));
    restored.workspace.x = Integer.MIN_VALUE;
    restored.workspace.y = Integer.MAX_VALUE;
    restored.workspace.maximized = true;
    restored.save(file);
    restored = UserConfig.read(file);
    assertEquals(Integer.MIN_VALUE, restored.workspace.x.intValue());
    assertEquals(Integer.MAX_VALUE, restored.workspace.y.intValue());
    assertTrue(restored.workspace.maximized);
  }

  @Test
  void partialPositionsFractionsNullSizesAndCoercedMaximizeCannotBeRead() throws Exception {
    Path file = directory.resolve("user.json");
    for (var raw :
        List.of(
            "{\"x\":5}",
            "{\"x\":null,\"y\":5}",
            "{\"x\":1.5,\"y\":2}",
            "{\"x\":2147483648,\"y\":2}",
            "{\"width\":null}",
            "{\"height\":299}",
            "{\"maximized\":\"true\"}",
            "{\"maximized\":null}")) {
      Files.writeString(file, "{\"formatVersion\":1,\"workspace\":" + raw + "}");
      assertThrows(IllegalArgumentException.class, () -> UserConfig.read(file), raw);
    }
    var user = new UserConfig();
    user.workspace.x = 2;
    assertThrows(IllegalArgumentException.class, user::validate);
    user.workspace.y = 3;
    user.validate();
  }

  static UserConfig.Workspace workspace(Integer x, Integer y, int width, int height) {
    var workspace = new UserConfig.Workspace();
    workspace.x = x;
    workspace.y = y;
    workspace.width = width;
    workspace.height = height;
    return workspace;
  }
}
