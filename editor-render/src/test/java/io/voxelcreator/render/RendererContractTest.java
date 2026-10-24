package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class RendererContractTest {
  @Test
  void greedyCubeAndDifferentTypeInternalFaceCulling() {
    assertEquals(36, MeshBuilder.greedy(8, (x, y, z) -> 1).vertexCount());
    var mixed = MeshBuilder.greedy(2, (x, y, z) -> x == 0 ? 1 : 2);
    assertEquals(60, mixed.vertexCount());
  }

  @Test
  void meshBoundary256DoesNotWrap() {
    var mesh = MeshBuilder.greedy(256, (x, y, z) -> 1);
    float max = 0;
    for (int i = 0; i < mesh.vertices().length; i += 7)
      for (int a = 0; a < 3; a++) max = Math.max(max, mesh.vertices()[i + a]);
    assertEquals(256, max);
  }

  @Test
  void cameraTeleportPreservesOrientationAndOffset() {
    var c = new Camera();
    var before = c.eye().sub(c.focus);
    double yaw = c.yaw, pitch = c.pitch;
    c.teleport(1e8, -20, -1e8);
    assertEquals(0, c.eye().sub(c.focus).distance(before), 1e-7);
    assertEquals(yaw, c.yaw);
    assertEquals(pitch, c.pitch);
    c.enterFly();
    c.teleport(-1, 2, 3);
    assertEquals(-1, c.eye().x);
    c.leaveFly();
    assertFalse(c.flying);
  }

  @Test
  void raycastTraversesThinAndNegativeCellsExactly() {
    var ray = new Camera.Ray(new org.joml.Vector3d(.5, .5, .5), new org.joml.Vector3d(1, 0, 0));
    assertEquals(
        new io.voxelcreator.storage.BlockPos(2, 0, 0),
        VoxelRaycast.firstOccupied(ray, 10, (x, y, z) -> x == 2 ? 1 : 0).orElseThrow());
    var back =
        new Camera.Ray(new org.joml.Vector3d(.01, .5, .5), new org.joml.Vector3d(-1, .001, 0));
    assertEquals(
        -1, VoxelRaycast.firstOccupied(back, 10, (x, y, z) -> x == -1 ? 1 : 0).orElseThrow().x());
    assertTrue(VoxelRaycast.firstOccupied(ray, 1, (x, y, z) -> x == 2 ? 1 : 0).isEmpty());
  }
}
