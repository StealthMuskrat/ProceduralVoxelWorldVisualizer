package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.api.Profiler;
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
  void optionalCaptureMeasuresActualMeshWorkWithChunkIdentity() throws Exception {
    try (var profiler = new Profiler();
        var workers = Executors.newFixedThreadPool(2)) {
      var capture = profiler.start(Profiler.Options.defaults());
      try (var scene =
          new ChunkScene(
              (p, t) -> SceneChunk.exact(VoxelChunk.uniform(p, 4, 1)),
              workers,
              config(),
              new RenderPressure(),
              p -> profiler.scope(0, null, p))) {
        settle(scene, new Vector3d(1, 1, 1));
        var mesh =
            capture.snapshot().samples().stream()
                .filter(s -> s.kind() == Profiler.Kind.MESH_CPU)
                .findFirst()
                .orElseThrow();
        assertEquals(new ChunkPos(0, 0, 0), mesh.chunk());
        assertEquals(Profiler.Outcome.SUCCESS, mesh.outcome());
        assertTrue(mesh.value() >= 0);
        assertTrue(scene.statistics().meshes() > 0);
      }
    }
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
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      do {
        scene.tick(new Vector3d(1), new Vector3d(10), 8);
        if (calls.get() > 0 && scene.statistics().activeJobs() == 0) break;
        Thread.sleep(1);
      } while (System.nanoTime() < deadline);
      assertEquals(0, scene.statistics().activeJobs(), "failure jobs did not settle");
      int first = calls.get();
      assertTrue(first > 0, "source was never scheduled");
      for (int i = 0; i < 100; i++) scene.tick(new Vector3d(1), new Vector3d(10), 8);
      assertTrue(calls.get() <= 125);
      assertEquals(first, calls.get(), "failed chunks were retried");
      assertTrue(scene.statistics().status().contains("bad field"));
    }
  }
}
