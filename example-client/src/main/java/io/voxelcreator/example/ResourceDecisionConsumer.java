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
    check(out.resolve("resource-selection-applied.json"), List.of("a", "renamed-b", "c"), 17, .875);
    check(out.resolve("resource-selection-undone.json"), List.of("a", "renamed-b", "c"), 17, .125);
    check(out.resolve("resource-selection-redone.json"), List.of("a", "renamed-b", "c"), 17, .875);
    System.out.println(
        "Resource decision engine-only PASS; one combined rename/seed edit, exact Undo/Redo and"
            + " selection Apply/Undo/Redo and 735 committed scalar values");
  }

  private static void check(Path path, List<String> ids, long firstSeed) throws Exception {
    check(path, ids, firstSeed, .125);
  }

  private static void check(Path path, List<String> ids, long firstSeed, double firstValue)
      throws Exception {
    var loaded = new ProjectLoader().load(path);
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (!p.noise2D.stream().map(n -> n.id).toList().equals(ids)
        || p.noise2D.getFirst().seedOffset != firstSeed)
      throw new AssertionError("Combined resource edit mismatch");
    var compiled = new CompiledProject(p);
    double[] values = {firstValue, .25, .5};
    for (int i = 0; i < ids.size(); i++)
      for (int x = -17; x <= 17; x++)
        if (compiled.noise2D(ids.get(i), 42).sample(x, -x) != values[i])
          throw new AssertionError("Resource scalar changed");
    if (!p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Unexpected World publication");
  }
}
