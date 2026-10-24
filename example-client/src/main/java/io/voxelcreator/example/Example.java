package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Independent consumer importing only the published headless engine artifact. */
public final class Example {
  public static ProjectData demo() {
    var p = new ProjectData();
    p.project.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");
    p.worldGenerator.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000002");
    var t = new VoxelType();
    t.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000003");
    t.id = "stone";
    p.voxelTypes.add(t);
    var n = new Noise();
    n.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000004");
    n.id = "terrain";
    n.algorithm.parameters.put("frequency", .03);
    p.noise2D.add(n);
    var volume = new Noise();
    volume.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000005");
    volume.id = "density";
    volume.dimension = 3;
    p.noise3D.add(volume);
    var layer = new Layer();
    layer.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000006");
    layer.id = "terrain";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = t.uuid;
    layer.defaults.baseHeight = 16;
    layer.defaults.heightScale = 12;
    var op = new Operation();
    op.uuid = java.util.UUID.fromString("00000000-0000-4000-8000-000000000007");
    op.id = "surface";
    op.noiseGeneratorUuid = n.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--contracts")) {
      ContractGenerator.write(Path.of(args.length > 1 ? args[1] : "."));
      return;
    }
    Path working = Path.of(args.length > 0 ? args[0] : "example-output");
    Files.createDirectories(working);
    var p = demo();
    Path file = working.resolve("terrain.pvproj.json");
    new ProjectIO().save(file, p);
    var loaded = new ProjectLoader().load(file);
    if (!loaded.success()) throw new IllegalStateException(loaded.diagnostics().toString());
    var compiled = new ProjectCompiler().compileWorld(loaded.project()).compiled();
    try (var engine = new NoiseGeneratorEngine(2, 16, 256L << 20);
        var world =
            engine.openWorld(
                compiled,
                new WorldOptions(
                    42L, 16, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 16L << 20))) {
      var sampler = compiled.noise2D("terrain", 42);
      double[] grid = new double[64];
      sampler.sampleGrid(-4, -4, 8, 8, 1, grid);
      System.out.println("Direct sample: " + sampler.sample(-1, 3));
      VoxelChunk chunk = world.generateChunk(new ChunkPos(-1, 0, 0));
      var asynchronous = world.generateChunkAsync(new ChunkPos(0, 0, 0)).get(5, TimeUnit.SECONDS);
      System.out.println("Async chunk: " + asynchronous.representation());
      var request =
          new RegionRequest(
              new RegionBounds(new BlockPos(-16, 0, 0), new BlockPos(15, 15, 15)),
              RegionRequest.FailurePolicy.CONTINUE,
              RegionRequest.DeliveryOrder.COORDINATE,
              2);
      var stream =
          world.streamRegion(
              request,
              c -> {
                System.out.println("Delivered " + c.position());
                return CompletableFuture.completedFuture(null);
              });
      var summary = stream.completion().get(10, TimeUnit.SECONDS);
      if (summary.progress().completed() != 2) throw new IllegalStateException(summary.toString());
      var cancellation =
          world.streamRegion(
              RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1023, 15, 15))),
              c -> new CompletableFuture<>());
      cancellation.cancel();
      cancellation.completion().get(5, TimeUnit.SECONDS);
      Path archive = working.resolve("exact-world.pvw");
      VoxelArchive.writeSingleChunk(archive, chunk, compiled.registry());
      var roundtrip = VoxelArchive.readSingleChunk(archive, VoxelRegistry.semantic("stone"));
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++)
            if (chunk.getVoxelTypeId(x, y, z) != roundtrip.getVoxelTypeId(x, y, z))
              throw new AssertionError("Archive mismatch");
      System.out.println("Archive and independent engine consumer PASS; " + world.cacheStats());
    }
  }
}
