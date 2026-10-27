package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.storage.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SurfaceSceneTest {
  private ChunkScene.Source source() {
    return new ChunkScene.Source() {
      public boolean surfaces() {
        return true;
      }

      public SceneChunk load(ChunkPos p, CancellationToken t) {
        return SceneChunk.isosurface(p, 4, t, (x, y, z) -> z - 1.257, 0);
      }
    };
  }

  private ChunkScene.Config config(long bytes) {
    return new ChunkScene.Config(4, 0, 0, 1, 2, 2, bytes, false, 27);
  }

  private void settle(ChunkScene scene, Vector3d center) throws Exception {
    long end = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < end) {
      scene.tick(center, new Vector3d(center).add(0, 0, 40), 8);
      for (var upload : scene.uploads(4, 1 << 20)) {
        assertNull(upload.cells());
        assertEquals(128, upload.mesh().unitsPerBlock());
        scene.uploaded(upload);
      }
      scene.displaySet();
      if (scene.statistics().meshed() > 0 && scene.statistics().activeJobs() == 0) return;
      Thread.sleep(1);
    }
    fail(scene.statistics().toString());
  }

  @Test
  void surfacesStreamAcrossSignedTravelWithoutChangingEpochOrInventingVoxelPicks()
      throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene = new ChunkScene(source(), workers, config(16L << 20))) {
      long epoch = scene.epoch();
      settle(scene, new Vector3d(1, 1, 1));
      long generated = scene.statistics().generated();
      settle(scene, new Vector3d(9, 1, 1));
      settle(scene, new Vector3d(-9, 1, 1));
      assertEquals(epoch, scene.epoch());
      assertTrue(scene.statistics().generated() > generated);
      assertTrue(scene.wanted().contains(new ChunkPos(-3, 0, 0)));
      assertTrue(scene.statistics().resident() <= 27);
      assertTrue(scene.statistics().cpuBytes() + scene.statistics().reserved() <= 16L << 20);
      assertTrue(
          scene
              .pick(
                  new Camera.Ray(new Vector3d(1, 1, 5), new Vector3d(0, 0, -1)),
                  20,
                  java.util.Map.of())
              .isEmpty());
    }
  }

  @Test
  void exactMemoryAdmissionRetainsVisibleBudgetReasonWithoutLeakingReservations() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var source =
          new ChunkScene.Source() {
            public boolean surfaces() {
              return true;
            }

            public SceneChunk load(ChunkPos p, CancellationToken t) {
              return SceneChunk.isosurface(p, 8, t, (x, y, z) -> z - 1.25, 0);
            }
          };
      var c = new ChunkScene.Config(8, 0, 0, 1, 1, 2, 1 << 20, false, 27);
      try (var scene = new ChunkScene(source, workers, c)) {
        for (int i = 0; i < 300; i++) {
          scene.tick(new Vector3d(1), new Vector3d(1, 1, 20), 8);
          Thread.sleep(1);
        }
        assertEquals(0, scene.statistics().meshed());
        assertTrue(scene.statistics().status().contains("CPU budget"));
        assertTrue(scene.statistics().cpuBytes() + scene.statistics().reserved() <= 1 << 20);
      }
    }
  }

  @Test
  void closureCancelsAnInFlightSurfaceWithoutPublishingItsLateResult() throws Exception {
    var entered = new CountDownLatch(1);
    var closed = new AtomicBoolean();
    try (var workers = Executors.newFixedThreadPool(1)) {
      var source =
          new ChunkScene.Source() {
            public boolean surfaces() {
              return true;
            }

            public SceneChunk load(ChunkPos p, CancellationToken t) {
              entered.countDown();
              while (!t.isCancelled()) Thread.onSpinWait();
              t.throwIfCancelled();
              return null;
            }

            public void close() {
              closed.set(true);
            }
          };
      var scene = new ChunkScene(source, workers, config(16L << 20));
      scene.tick(new Vector3d(1), new Vector3d(1, 1, 20), 8);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      scene.close();
      workers.shutdown();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
      scene.tick(new Vector3d(1), new Vector3d(1), 8);
      assertTrue(scene.uploads(4, 1 << 20).isEmpty());
      assertEquals(0, scene.statistics().reserved());
      assertTrue(closed.get());
    }
  }

  @Test
  void queueRejectionReclaimsTheSlotAndRetriesAfterCapacityReturns() throws Exception {
    var workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    var gate = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    workers.submit(
        () -> {
          entered.countDown();
          try {
            gate.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    workers.submit(() -> {});
    try (var scene = new ChunkScene(source(), workers, config(16L << 20))) {
      scene.tick(new Vector3d(1), new Vector3d(1, 1, 20), 8);
      assertTrue(scene.rejected());
      assertEquals(0, scene.statistics().reserved());
      gate.countDown();
      settle(scene, new Vector3d(1));
      assertTrue(scene.statistics().meshes() > 0);
    } finally {
      gate.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void sourceGeometryMismatchRejectsInsteadOfReinterpretingVoxelInputs() throws Exception {
    try (var workers = Executors.newFixedThreadPool(1);
        var scene =
            new ChunkScene(
                new ChunkScene.Source() {
                  public boolean surfaces() {
                    return true;
                  }

                  public SceneChunk load(ChunkPos p, CancellationToken t) {
                    return SceneChunk.exact(VoxelChunk.uniform(p, 4, 1));
                  }
                },
                workers,
                config(16L << 20))) {
      for (int i = 0; i < 100; i++) {
        scene.tick(new Vector3d(1), new Vector3d(1, 1, 20), 8);
        Thread.sleep(1);
      }
      assertEquals(0, scene.statistics().generated());
      assertEquals(0, scene.statistics().meshes());
      assertTrue(scene.statistics().status().contains("mismatched"));
    }
  }
}
