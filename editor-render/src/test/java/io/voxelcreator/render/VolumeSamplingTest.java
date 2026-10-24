package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

public class VolumeSamplingTest {
  @Test
  void textureAxisOrderAndCellCentersPreserveAffineField() {
    var data = VolumeData.sample(4, -3, 7, -11, (x, y, z) -> x + 10 * y + 100 * z);
    assertEquals(-3 + .5 + 10 * (7 + .5) + 100 * (-11 + .5), data.get(0, 0, 0));
    assertEquals(-3 + 2.5 + 10 * (7 + 1.5) + 100 * (-11 + 3.5), data.get(2, 1, 3));
    assertEquals(
        -3 + 1.25 + 10 * (7 + 2.25) + 100 * (-11 + 1.75),
        data.interpolate(1.25, 2.25, 1.75),
        1e-12);
    assertEquals(data.get(0, 0, 0), data.interpolate(0, 0, 0));
    assertEquals(data.get(3, 3, 3), data.interpolate(4, 4, 4));
  }

  @Test
  void displaySnapshotIsImmutableAndFiniteWithoutChangingCanonicalField() {
    float[] values = new float[8];
    values[3] = 7;
    var data = new VolumeData(2, values);
    values[3] = 0;
    assertEquals(7, data.get(1, 1, 0));
    float[] exported = data.copySamples();
    exported[3] = 99;
    assertEquals(7, data.get(1, 1, 0));
    var extreme = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> Double.MAX_VALUE);
    assertEquals(Float.MAX_VALUE, extreme.get(0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> VolumeData.sample(2, 0, 0, 0, (x, y, z) -> Double.NaN));
  }

  @Test
  void cancellationStopsTextureSampling() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          CancellationException.class, () -> VolumeData.sample(32, 0, 0, 0, (x, y, z) -> 1));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void sliceMappingsKeepTheDeclaredNormalFixed() {
    for (var plane : SlicePlane.values()) {
      double x = plane.x(-7, 13, 5), y = plane.y(-7, 13, 5), z = plane.z(-7, 13, 5);
      switch (plane) {
        case X -> {
          assertEquals(5, x);
          assertEquals(-7, y);
          assertEquals(13, z);
        }
        case Y -> {
          assertEquals(-7, x);
          assertEquals(5, y);
          assertEquals(13, z);
        }
        case Z -> {
          assertEquals(-7, x);
          assertEquals(13, y);
          assertEquals(5, z);
        }
      }
    }
  }
}
