package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

public class ChunkSceneTest {
  private static ChunkScene.Config config() {
    return new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, false, 125);
  }

  private static void settle(ChunkScene scene, Vector3d focus) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      scene.tick(focus, new Vector3d(focus).add(0, 0, 20), 8);
      for (var upload : scene.uploads(8, 1 << 20)) scene.uploaded(upload);
      scene.displaySet();
      var s = scene.statistics();
      if (s.meshed() > 0 && s.activeJobs() == 0) return;
      Thread.sleep(1);
    }
    throw new AssertionError(scene.statistics());
  }

  @Test
  void cameraTravelReusesEpochAndEvictsObsoleteData() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene =
            new ChunkScene(
                (p, t) -> SceneChunk.exact(VoxelChunk.uniform(p, 4, p.y() <= 0 ? 1 : 0)),
                workers,
                config())) {
      long epoch = scene.epoch();
      settle(scene, new Vector3d(1, 1, 1));
      long generated = scene.statistics().generated();
      settle(scene, new Vector3d(17, 1, -17));
      assertEquals(epoch, scene.epoch());
      assertTrue(scene.statistics().generated() > generated);
      assertTrue(scene.wanted().contains(new ChunkPos(4, 0, -5)));
      assertTrue(scene.statistics().resident() <= 125);
      assertTrue(scene.statistics().cpuBytes() + scene.statistics().reserved() <= 64L << 20);
    }
  }

  @Test
  void cancelledEpochDoesNotPublishAnOldSource() throws Exception {
    var entered = new CountDownLatch(1);
    var closed = new AtomicBoolean();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var source =
          new ChunkScene.Source() {
            public SceneChunk load(ChunkPos p, CancellationToken token) {
              entered.countDown();
              while (!token.isCancelled()) {
                try {
                  Thread.sleep(1);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
              token.throwIfCancelled();
              return SceneChunk.exact(VoxelChunk.uniform(p, 4, 1));
            }

            public void close() {
              closed.set(true);
            }
          };
      var scene = new ChunkScene(source, workers, config());
      scene.tick(new Vector3d(1), new Vector3d(10), 8);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      scene.close();
      assertTrue(closed.get());
      assertTrue(scene.uploads(8, 1 << 20).isEmpty());
      assertEquals(0, scene.statistics().cpuBytes());
    }
  }

  @Test
  void runtimeFailureIsReportedAndNotRetriedEveryFrame() throws Exception {
    var calls = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(2);
        var scene =
            new ChunkScene(
                (p, t) -> {
                  calls.incrementAndGet();
                  throw new ArithmeticException("bad field");
                },
                workers,
                config())) {
      for (int i = 0; i < 30; i++) {
        scene.tick(new Vector3d(1), new Vector3d(10), 8);
        Thread.sleep(1);
      }
      int first = calls.get();
      for (int i = 0; i < 100; i++) scene.tick(new Vector3d(1), new Vector3d(10), 8);
      assertTrue(calls.get() <= 125);
      assertTrue(scene.statistics().status().contains("bad field"));
      assertTrue(first > 0);
    }
  }
}
