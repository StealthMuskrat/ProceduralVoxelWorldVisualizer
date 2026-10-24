package io.voxelcreator.render;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;

/** Explicit benchmark entry point; never run automatically during ordinary editing. */
public final class Benchmark {
  public static void main(String[] args) throws Exception {
    System.out.println(
        "# java="
            + System.getProperty("java.runtime.version")
            + " os="
            + System.getProperty("os.name")
            + " arch="
            + System.getProperty("os.arch")
            + " reported_processors="
            + Runtime.getRuntime().availableProcessors()
            + " heap_limit="
            + Runtime.getRuntime().maxMemory());
    System.out.println(
        "# Method: one caller, NO_CACHE, fixed [0,63]^3 cells, 2 warmups then 5 measured runs per"
            + " scene/root, generation and greedy meshing separately. GPU not measured.");
    System.out.println(
        "scene,root,run,generation_ms,meshing_ms,snapshot_bytes,vertices,voxel_hash");
    for (String scene : List.of("empty", "sparse", "dense", "mixed", "high_frequency")) {
      Long reference = null;
      for (int root : new int[] {32, 64})
        for (int run = -2; run < 5; run++) {
          ProjectData p = scene(scene);
          var compiled = new CompiledProject(p);
          int across = 64 / root;
          var chunks = new VoxelChunk[across * across * across];
          long bytes = 0, start = System.nanoTime();
          try (var engine = new NoiseGeneratorEngine(1, 16, 512L << 20);
              var world =
                  engine.openWorld(
                      compiled,
                      new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = 0; z < across; z++)
              for (int y = 0; y < across; y++)
                for (int x = 0; x < across; x++) {
                  var chunk = world.generateChunk(new ChunkPos(x, y, z));
                  chunks[(z * across + y) * across + x] = chunk;
                  bytes += chunk.estimatedBytes();
                }
          }
          long generated = System.nanoTime();
          MeshBuilder.Cells cells =
              (x, y, z) ->
                  chunks[((z / root) * across + y / root) * across + x / root].getVoxelTypeId(
                      x % root, y % root, z % root);
          var mesh = MeshBuilder.greedy(64, cells);
          long meshed = System.nanoTime();
          long hash = 0xcbf29ce484222325L;
          for (int z = 0; z < 64; z++)
            for (int y = 0; y < 64; y++)
              for (int x = 0; x < 64; x++) {
                hash ^= cells.get(x, y, z);
                hash *= 0x100000001b3L;
              }
          if (reference == null) reference = hash;
          else if (reference.longValue() != hash)
            throw new AssertionError("Partition changed exact voxels for " + scene);
          if (run >= 0)
            System.out.printf(
                Locale.ROOT,
                "%s,%d,%d,%.4f,%.4f,%d,%d,%016x%n",
                scene,
                root,
                run,
                (generated - start) / 1e6,
                (meshed - generated) / 1e6,
                bytes,
                mesh.vertexCount(),
                hash);
        }
    }
  }

  private static ProjectData scene(String name) {
    var p = new ProjectData();
    p.project.uuid = UUID.fromString("00000000-0000-4000-8000-000000000001");
    p.project.seed = 42;
    p.project.minY = 0;
    p.project.maxY = 63;
    var t = new VoxelType();
    t.id = "stone";
    t.uuid = UUID.fromString("00000000-0000-4000-8000-000000000002");
    p.voxelTypes.add(t);
    var n = new Noise();
    n.id = "source";
    n.uuid = UUID.fromString("00000000-0000-4000-8000-000000000003");
    if (name.equals("dense")) {
      n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.put("value", 0.);
    }
    if (name.equals("high_frequency") || name.equals("sparse")) {
      n.dimension = 3;
      n.algorithm.key = name.equals("sparse") ? "WHITE_HASH" : "CELLULAR";
      n.algorithm.parameters.put("frequency", .4);
    } else n.algorithm.parameters.put("frequency", .03);
    (n.dimension == 2 ? p.noise2D : p.noise3D).add(n);
    var l = new Layer();
    l.id = "layer";
    l.uuid = UUID.fromString("00000000-0000-4000-8000-000000000004");
    l.defaults.action = "SET";
    l.defaults.voxelTypeUuid = t.uuid;
    l.defaults.baseHeight = name.equals("dense") ? 64 : 20;
    l.defaults.heightScale = 14;
    l.defaults.threshold = name.equals("sparse") ? .995 : -.5;
    var op = new Operation();
    op.id = "placement";
    op.uuid = UUID.fromString("00000000-0000-4000-8000-000000000005");
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = n.dimension == 2 ? "HEIGHT_BELOW" : "DENSITY";
    l.operations.add(op);
    if (!name.equals("empty")) p.worldGenerator.layers.add(l);
    return p;
  }
}
