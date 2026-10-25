package io.voxelcreator.example;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;

/** Independent engine-only consumer of the explicit native workflow's portable selection. */
public final class ResourceSelectionConsumer {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the portable selection file");
    var tree = ProjectIO.JSON.readTree(Path.of(args[0]).toFile());
    if (tree.path("packageVersion").asInt() != 1)
      throw new AssertionError("Wrong portable version");
    var p = ProjectIO.JSON.treeToValue(tree.path("resources"), ProjectData.class);
    if (!Validator.validate(p).isEmpty()) throw new AssertionError(Validator.validate(p));
    if (!p.voxelTypes.stream().map(t -> t.id).toList().equals(List.of("type-00000", "type-01999"))
        || p.voxelSets.size() != 1
        || !p.voxelSets.getFirst().id.equals("catalog"))
      throw new AssertionError("Wrong selected membership payload");
    if (!p.voxelSets
        .getFirst()
        .voxelTypeUuids
        .equals(p.voxelTypes.stream().map(t -> t.uuid).toList()))
      throw new AssertionError("Membership references changed");
    var compiled = new CompiledProject(p);
    for (int x = -19; x <= 19; x++)
      if (compiled.noise2D("shared-field", 42).sample(x, -x) != .625)
        throw new AssertionError("Independent constant mismatch");
    if (!p.noise3D.isEmpty() || !p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Unselected resources leaked");
    System.out.println(
        "Independent selection package PASS; engine-only membership identity, exact closure, empty"
            + " world and constant expectations");
  }
}
