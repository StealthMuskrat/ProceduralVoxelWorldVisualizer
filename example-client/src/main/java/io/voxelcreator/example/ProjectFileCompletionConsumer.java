package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;
import java.util.List;

/**
 * Deferred engine-only complete file publication and deterministic source/candidate/empty replay.
 */
public final class ProjectFileCompletionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceConsumer.main(new String[] {out.resolve("source-project.json").toString()});
    var source = ProjectIO.JSON.readTree(out.resolve("source-project.json").toFile());
    var candidate = source.deepCopy();
    ((ObjectNode) candidate.path("noise2D").get(0).path("algorithm").path("parameters"))
        .put("value", 4.);
    require(
        candidate.equals(read(out, "candidate-project.json"))
            && candidate.equals(read(out, "opened-project.json")),
        "Open publication changed other project values");
    require(
        source.equals(read(out, "saved-project.json")) && source.equals(read(out, "after.json")),
        "Standalone save or cancelled routes published private input");
    for (var phase : List.of("opened-project", "new-project")) {
      var load = new ProjectLoader().load(out.resolve(phase + ".json"));
      require(load.success(), load.diagnostics().toString());
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
          var world = engine.openWorld(new CompiledProject(load.project()))) {
        var pos = new BlockPos(2, 0, 3);
        var trace = world.trace(pos);
        boolean empty = phase.equals("new-project");
        require(
            trace.finalType() == (empty ? 0 : 1) && world.getVoxelTypeId(pos) == trace.finalType(),
            "Replacement world/trace mismatch");
        if (empty)
          require(
              load.project().noise2D.isEmpty()
                  && load.project().noise3D.isEmpty()
                  && load.project().worldGenerator.layers.isEmpty()
                  && trace.steps().isEmpty(),
              "New retained authored resources");
        else
          require(
              trace.steps().size() == 2
                  && trace.steps().stream()
                      .allMatch(step -> step.field() == (4 + 2 * (2 + 1) + 3) * 2)
                  && trace.steps().getFirst().action().equals("SET")
                  && trace.steps().getLast().action().equals("NO_CHANGE"),
              "Independent candidate field-26 oracle changed");
        require(
            ProjectIO.JSON
                .readTree(ProjectIO.JSON.writeValueAsString(trace))
                .equals(read(out, phase + "-trace.json")),
            "Complete replacement trace replay differs");
      }
    }
    require(
        read(out, "preferences-before.json").equals(read(out, "preferences.json")),
        "File completion changed preferences");
    System.out.println(
        "File completion engine-only PASS; original field 22 / exact candidate Open and field 26 /"
            + " New EMPTY / committed-only Save / complete traces/preferences");
  }

  private static com.fasterxml.jackson.databind.JsonNode read(Path out, String name)
      throws Exception {
    return ProjectIO.JSON.readTree(out.resolve(name).toFile());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
