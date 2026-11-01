package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;

/**
 * Deferred headless source/import/Undo/package replay for exact review lifetime and publication.
 */
public final class ResourceReviewLifetimeConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceConsumer.main(new String[] {out.resolve("source-project.json").toString()});
    var source = read(out, "source-project.json");
    require(
        source.equals(read(out, "after.json")) && source.equals(read(out, "import-undo.json")),
        "Review lifetime changed final/undone source");
    var load = new ProjectLoader().load(out.resolve("imported-project.json"));
    require(load.success(), load.diagnostics().toString());
    var project = load.project();
    require(project.noise2D.size() == 2, "Import did not append exactly one resource");
    var added =
        project.noise2D.stream()
            .filter(noise -> noise.id.equals("added"))
            .findFirst()
            .orElseThrow();
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
        var world = engine.openWorld(new CompiledProject(project))) {
      var pos = new BlockPos(2, 0, 3);
      var trace = world.trace(pos);
      require(
          trace.finalType() == 1
              && world.getVoxelTypeId(pos) == 1
              && trace.steps().stream().allMatch(step -> step.field() == 22),
          "Import changed original field/world");
      var sampler = new CompiledProject(project).noise2D(added.uuid, project.project.seed);
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++)
          require(sampler.sample(x, z) == .75, "Imported constant oracle changed");
    }
    var envelope = read(out, "selection.pvresources.json");
    require(envelope.path("packageVersion").intValue() == 1, "Wrong envelope version");
    var parsed =
        new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(envelope.path("resources")));
    require(parsed.success(), parsed.diagnostics().toString());
    var portable = parsed.project();
    require(
        portable.noise2D.size() == 1
            && portable.worldGenerator.layers.isEmpty()
            && portable.voxelTypes.isEmpty(),
        "Selected noise export became a whole project");
    var compiled = new CompiledProject(portable);
    require(
        compiled.noise2D(portable.noise2D.getFirst().uuid, portable.project.seed).sample(2, 3)
            == 22,
        "Portable exact field oracle changed");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
        var world = engine.openWorld(compiled)) {
      require(
          world.getVoxelTypeId(new BlockPos(2, 0, 3)) == 0,
          "Portable noise-only world was not EMPTY");
    }
    require(
        read(out, "preferences-before.json").equals(read(out, "preferences.json")),
        "Reviews changed preferences");
    System.out.println(
        "Resource review engine-only PASS; original field 22 / 81 imported .75 samples / one import"
            + " Undo / exact noise-only package and EMPTY world / original preferences");
  }

  private static com.fasterxml.jackson.databind.JsonNode read(Path out, String name)
      throws Exception {
    return ProjectIO.JSON.readTree(out.resolve(name).toFile());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
