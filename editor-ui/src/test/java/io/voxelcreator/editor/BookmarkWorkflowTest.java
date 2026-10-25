package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.Camera;
import java.util.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class BookmarkWorkflowTest {
  static Bookmark bookmark(ProjectData p, Tab tab, String name, int x, int y, int z) {
    var b = new Bookmark(); b.name = name; b.x = x; b.y = tab.threeDimensional() ? y : null; b.z = z;
    BookmarkTransactions.list(p, tab).add(b);
    return b;
  }

  @Test void indexOwnsRowsAndAllowsDuplicateNamesWithDifferentCoordinatesAndUuids() {
    var p = new ProjectData();
    var a = bookmark(p, Tab.WORLD, "duplicate", -1, 2, 3);
    var b = bookmark(p, Tab.WORLD, "duplicate", 4, 5, 6);
    var index = new BookmarkIndex(p, Tab.WORLD);
    a.name = "changed"; a.x = 99; p.bookmarks.world.clear();
    assertEquals(2, index.size());
    assertEquals("duplicate", index.get(a.uuid).name());
    assertEquals(-1, index.get(a.uuid).coordinates().x());
    assertNotEquals(index.get(a.uuid).label(), index.get(b.uuid).label());
    assertNull(index.get(null));
    assertThrows(UnsupportedOperationException.class, () -> index.rows("").clear());
  }

  @Test void filteringCachesNormalizedQueriesAndSearchesSignedCoordinates() {
    var p = new ProjectData();
    var a = bookmark(p, Tab.NOISE_3D, "Northern Range", -17, -23, 31);
    bookmark(p, Tab.NOISE_3D, "Southern Range", 41, 43, 47);
    var index = new BookmarkIndex(p, Tab.NOISE_3D);
    var rows = index.rows(" NORTHERN ");
    assertEquals(List.of(a.uuid), rows.stream().map(BookmarkIndex.Row::uuid).toList());
    assertSame(rows, index.rows("northern"));
    assertEquals(1, index.projections());
    assertEquals(List.of(a.uuid), index.rows("y -23").stream().map(BookmarkIndex.Row::uuid).toList());
    assertTrue(index.rows("missing").isEmpty());
  }

  @Test void cacheTracksCommittedProjectAndRevisionAndSeparatesTabs() {
    var p = new ProjectData();
    bookmark(p, Tab.NOISE_2D, "2d", 1, 0, 3);
    var cache = new BookmarkIndex.Cache();
    var first = cache.get(p, 1, Tab.NOISE_2D);
    assertSame(first, cache.get(p, 1, Tab.NOISE_2D));
    assertNotSame(first, cache.get(p, 1, Tab.WORLD));
    assertEquals(2, cache.builds());
    assertNotSame(first, cache.get(p, 2, Tab.NOISE_2D));
    assertNotSame(first, cache.get(ProjectIO.copy(p), 2, Tab.NOISE_2D));
    assertEquals(4, cache.builds());
  }

  @Test void bookmarkEditsDoNotChangeNoiseOrWorldComputationIdentity() {
    var p = new ProjectData();
    var n = new Noise(); n.id = "constant"; n.algorithm.key = "CONSTANT"; p.noise2D.add(n);
    var noise = PreviewIdentity.of(p, 0, n.uuid);
    var world = PreviewIdentity.of(p, 2, null);
    for (var tab : Tab.values()) bookmark(p, tab, "same", -1, -3, -5);
    assertEquals(noise, PreviewIdentity.of(p, 0, n.uuid));
    assertEquals(world, PreviewIdentity.of(p, 2, null));
    p.bookmarks.world.getFirst().name = "changed";
    assertEquals(world, PreviewIdentity.of(p, 2, null));
  }

  @Test void orbitTeleportPreservesCameraOffsetAndOrientationWithoutEditingProject() {
    var p = new ProjectData(); var before = ProjectIO.JSON.valueToTree(p);
    var camera = new Camera(); camera.yaw = 1.2; camera.pitch = -.4; camera.distance = 73;
    var offset = camera.eye().sub(camera.focus);
    camera.teleport(-17, 23, -31);
    assertEquals(new Vector3d(-17, 23, -31), camera.focus);
    assertTrue(offset.distance(camera.eye().sub(camera.focus)) < 1e-12);
    assertEquals(1.2, camera.yaw); assertEquals(-.4, camera.pitch); assertEquals(73, camera.distance);
    assertFalse(camera.flying);
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test void freeFlyTeleportMovesOnlyPositionAndPreservesOrientation() {
    var camera = new Camera(); camera.enterFly();
    var oldFocus = new Vector3d(camera.focus);
    double yaw = camera.yaw, pitch = camera.pitch, distance = camera.distance;
    camera.teleport(Integer.MIN_VALUE, -7, Integer.MAX_VALUE);
    assertEquals(new Vector3d(Integer.MIN_VALUE, -7, Integer.MAX_VALUE), camera.position);
    assertEquals(oldFocus, camera.focus);
    assertEquals(yaw, camera.yaw); assertEquals(pitch, camera.pitch); assertEquals(distance, camera.distance);
    assertTrue(camera.flying);
  }
}
