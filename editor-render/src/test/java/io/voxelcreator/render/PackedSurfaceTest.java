package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class PackedSurfaceTest {
  @Test
  void finePositionsRespectHalfQuantumErrorAndRetainNormalsMaterialAndAo() {
    var raw = MeshBuilder.isosurface(2, (x, y, z) -> x - .257, 0);
    var packed = PackedMesh.surface(raw, 2);
    var bytes = packed.readOnly();
    assertEquals(128, packed.unitsPerBlock());
    assertEquals(raw.vertexCount(), packed.vertexCount());
    assertFalse(packed.points());
    for (int i = 0; i < packed.byteSize(); i += 16) {
      assertEquals(.257, Short.toUnsignedInt(bytes.getShort(i)) / 128., 1 / 256.);
      assertEquals(-127, bytes.get(i + 6));
      assertEquals(255, Byte.toUnsignedInt(bytes.get(i + 9)));
      assertEquals(128, bytes.getInt(i + 12));
    }
    assertEquals(2, new PackedMesh(new byte[0], false).unitsPerBlock());
  }

  @Test
  void endpoint256UsesUnsignedStorageWithoutWrapping() {
    var mesh =
        new Mesh(
            new float[] {256, 0, 0, 1, 0, 0, 128, 256, 1, 0, 1, 0, 0, 128, 256, 0, 1, 1, 0, 0, 128},
            false);
    var packed = PackedMesh.surface(mesh, 256);
    assertEquals(32768, Short.toUnsignedInt(packed.readOnly().getShort(0)));
    assertEquals(3, packed.vertexCount());
  }

  @Test
  void subQuantumDegeneratesAreRemovedAndInvalidCoordinatesReject() {
    var mesh =
        new Mesh(
            new float[] {
              0, 0, 0, 0, 0, 1, 128, .0001f, 0, 0, 0, 0, 1, 128, 0, .0001f, 0, 0, 0, 1, 128
            },
            false);
    assertEquals(0, PackedMesh.surface(mesh, 2).vertexCount());
    mesh.vertices()[0] = Float.NaN;
    assertThrows(IllegalArgumentException.class, () -> PackedMesh.surface(mesh, 2));
  }

  @Test
  void packedSnapshotsAreReadOnlyAndPackingObservesCancellation() {
    var mesh = MeshBuilder.isosurface(2, (x, y, z) -> x - .257, 0);
    var packed = PackedMesh.surface(mesh, 2);
    assertThrows(java.nio.ReadOnlyBufferException.class, () -> packed.readOnly().put(0, (byte) 9));
    var token = new CancellationToken();
    token.cancel();
    assertThrows(CancellationException.class, () -> PackedMesh.surface(mesh, 2, token));
  }
}
