package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay of one combined private-draft/browser edit and its Undo/Redo. */
public final class ResourceDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    check(out.resolve("resource-before.json"), List.of("a", "b", "c"), 0);
    check(out.resolve("resource-after.json"), List.of("a", "renamed-b", "c"), 17);
    check(out.resolve("resource-undone.json"), List.of("a", "b", "c"), 0);
    check(out.resolve("resource-redone.json"), List.of("a", "renamed-b", "c"), 17);
    System.out.println(
        "Resource decision engine-only PASS; one combined rename/seed edit, exact Undo/Redo and"
            + " committed field values");
  }

  private static void check(Path path, List<String> ids, long firstSeed) throws Exception {
    var loaded = new ProjectLoader().load(path);
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (!p.noise2D.stream().map(n -> n.id).toList().equals(ids)
        || p.noise2D.getFirst().seedOffset != firstSeed)
      throw new AssertionError("Combined resource edit mismatch");
    var compiled = new CompiledProject(p);
    double[] values = {.125, .25, .5};
    for (int i = 0; i < ids.size(); i++)
      for (int x = -17; x <= 17; x++)
        if (compiled.noise2D(ids.get(i), 42).sample(x, -x) != values[i])
          throw new AssertionError("Resource scalar changed");
    if (!p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Unexpected World publication");
  }
}
