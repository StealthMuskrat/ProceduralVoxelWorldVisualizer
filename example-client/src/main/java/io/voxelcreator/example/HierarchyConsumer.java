package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Engine-only independent expectations: tree organization is inert; execution SET/CLEAR is not. */
public final class HierarchyConsumer {
  public static ProjectData fixture() {
    var p = new ProjectData();
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var source = new Noise();
    source.id = "constant";
    source.dimension = 3;
    source.algorithm.key = "CONSTANT";
    source.algorithm.parameters.clear();
    source.algorithm.parameters.put("value", 1.0);
    p.noise3D.add(source);
    for (String action : List.of("SET", "CLEAR")) {
      var layer = new Layer();
      layer.id = action.toLowerCase(Locale.ROOT);
      layer.defaults.action = action;
      layer.defaults.voxelTypeUuid = type.uuid;
      var op = new Operation();
      op.id = "everywhere";
      op.noiseGeneratorUuid = source.uuid;
      op.interpretation = "DENSITY";
      layer.operations.add(op);
      p.worldGenerator.layers.add(layer);
    }
    var first = new Folder();
    first.id = "first";
    first.rootKind = "world";
    var second = new Folder();
    second.id = "second";
    second.rootKind = "world";
    p.folders.addAll(List.of(first, second));
    return p;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the organized project file");
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (!p.worldGenerator.layers.stream().map(l -> l.id).toList().equals(List.of("set", "clear")))
      throw new AssertionError("Organization changed execution order");
    if (p.worldGenerator.layers.stream().anyMatch(l -> l.parentFolderUuid == null))
      throw new AssertionError("World folders were lost");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4, 8}) {
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("SET then CLEAR should be EMPTY");
        }
      }
      ResourceGraph.shiftExecution(p, List.of(p.worldGenerator.layers.getLast().uuid), -1);
      try (var world =
          engine.openWorld(
              new CompiledProject(p),
              new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
        var chunk = world.generateChunk(new ChunkPos(0, 0, 0));
        for (int x = 0; x < 4; x++)
          for (int y = 0; y < 4; y++)
            for (int z = 0; z < 4; z++)
              if (chunk.getVoxelTypeId(x, y, z) == 0)
                throw new AssertionError("CLEAR then SET should be solid");
      }
    }
    System.out.println(
        "Independent hierarchy PASS; engine-only world folders / root order / SET-CLEAR invariance"
            + " / explicit execution change / root sizes and negative chunks");
  }
}
