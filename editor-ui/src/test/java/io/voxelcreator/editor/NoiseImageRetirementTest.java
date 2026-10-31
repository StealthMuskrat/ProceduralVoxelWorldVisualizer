package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class NoiseImageRetirementTest {
  private static NoiseImageRows.Image sample(
      NoiseImageRows rows, long epoch, NoiseAlgorithms.Field field) {
    return rows.sample(
        "same-key", epoch, field, 4, 0, 0, 1, 1L << 20, false, new CancellationToken());
  }

  @Test
  void clearRetiresRowsAndRejectsOldRequestsEvenWhenTheNewDocumentReusesTheKey() {
    try (var rows = new NoiseImageRows()) {
      var old = sample(rows, 1, (x, y, z) -> .125);
      assertEquals(4, rows.memory().rows());
      rows.clear(2);
      assertNull(rows.memory().source());
      assertEquals(0, rows.memory().bytes());
      sample(rows, 1, (x, y, z) -> .125);
      assertNull(rows.memory().source());
      assertEquals(0, rows.memory().rows());
      var next = sample(rows, 2, (x, y, z) -> .5);
      for (int z = 0; z < 4; z++)
        for (int x = 0; x < 4; x++) {
          assertEquals(.125, old.value(x, z));
          assertEquals(.5, next.value(x, z));
        }
      assertEquals(
          4,
          sample(
                  rows,
                  3,
                  (x, y, z) -> {
                    throw new AssertionError("Unexpected evaluation");
                  })
              .statistics()
              .reusedRows());
      assertThrows(IllegalArgumentException.class, () -> rows.clear(2));
    }
  }

  @Test
  void anUncooperativeOldSamplerCannotRepopulateTheClearedCache() throws Exception {
    try (var rows = new NoiseImageRows();
        var worker = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var old =
          worker.submit(
              () ->
                  sample(
                      rows,
                      1,
                      (x, y, z) -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Sampler release timeout");
                        } catch (InterruptedException interrupted) {
                          throw new CancellationException();
                        }
                        return .125;
                      }));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        rows.clear(2);
        sample(rows, 2, (x, y, z) -> .75);
      } finally {
        release.countDown();
      }
      assertEquals(.125, old.get(5, TimeUnit.SECONDS).value(0, 0));
      assertEquals(4, rows.memory().rows());
      var next =
          sample(
              rows,
              3,
              (x, y, z) -> {
                throw new AssertionError("Old work evicted new rows");
              });
      assertEquals(.75, next.value(0, 0));
      assertEquals(4, next.statistics().reusedRows());
    }
  }
}
