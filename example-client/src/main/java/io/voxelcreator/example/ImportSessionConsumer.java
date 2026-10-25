package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;

/** External engine-only import transaction with independently known SET/NO_CHANGE results. */
public final class ImportSessionConsumer {
  public static void main(String[] args) {
    var incoming = new ProjectData();
    var type = new VoxelType();
    type.id = "solid";
    incoming.voxelTypes.add(type);
    var keep = field("keep", 1);
    var skip = field("skip", -1);
    incoming.noise3D.addAll(List.of(keep, skip));
    var layer = new Layer();
    layer.id = "terrain";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = 0;
    incoming.worldGenerator.layers.add(layer);
    layer.operations.add(placement("keep-placement", keep.uuid));
    layer.operations.add(placement("skip-placement", skip.uuid));
    var document = new DocumentSession();
    var session = new ResourceImport.Session(document.data(), incoming);
    UUID copied = session.preview().rows().stream()
        .filter(r -> r.uuid().equals(keep.uuid)).findFirst().orElseThrow().targetUuid();
    session.choose(skip.uuid, ResourceImport.Choice.SKIP);
    var plan = session.preview();
    require(plan.valid(), plan.diagnostics().toString());
    require(plan.candidate().worldGenerator.layers.getFirst().operations.size() == 1,
        "Skip must remove only its dependent placement");
    require(plan.rows().stream().filter(r -> r.uuid().equals(keep.uuid))
        .findFirst().orElseThrow().targetUuid().equals(copied), "Stable preview UUID");
    require(document.edit(session::applyTo).isEmpty(), "Atomic import");
    require(document.data().worldGenerator.layers.getFirst().operations.getFirst().overrides.isEmpty(),
        "Missing overrides remain live");
    verify(document.data(), true);
    document.edit(p -> p.worldGenerator.layers.getFirst().defaults.action = "NO_CHANGE");
    verify(document.data(), false);
    document.undo();
    verify(document.data(), true);
    document.undo();
    require(document.data().noise3D.isEmpty(), "One import undo");
    document.redo();
    verify(document.data(), true);
    System.out.println("IMPORT_SESSION_ENGINE_ONLY_PASS stableCopyIds=true roots=2,4,8"
        + " negativeChunks=true liveInheritance=true undoRedo=true");
  }

  private static Noise field(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.dimension = 3;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static Operation placement(String id, UUID field) {
    var op = new Operation();
    op.id = id;
    op.noiseGeneratorUuid = field;
    op.interpretation = "DENSITY";
    return op;
  }

  private static void verify(ProjectData project, boolean occupied) {
    var result = new ProjectCompiler().compileWorld(project);
    require(result.success(), result.diagnostics().toString());
    try (var engine = new NoiseGeneratorEngine()) {
      for (int root : List.of(2, 4, 8)) {
        try (var world = engine.openWorld(result.compiled(),
            new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var chunk = world.generateChunk(new ChunkPos(-1, 0, -1));
          for (int y = 0; y < root; y++)
            for (int z = 0; z < root; z++)
              for (int x = 0; x < root; x++)
                require((chunk.getVoxelTypeId(x, y, z) != 0) == occupied, "Exact voxel oracle");
        }
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
