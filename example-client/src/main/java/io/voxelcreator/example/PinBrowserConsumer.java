package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import java.nio.file.*;

/** Deferred engine-JAR replay: canonical shared values and no generation dependence on pins. */
public final class PinBrowserConsumer {
  public static void main(String[] args) throws Exception {
    var result = new ProjectLoader().load(Path.of(args[0]));
    if (!result.success()) throw new AssertionError(result.diagnostics());
    var project = result.project();
    if (project.noise2D.getFirst().sharedParameters.getFirst().value.doubleValue() != 5)
      throw new AssertionError("Canonical shared parameter");
    var withoutPins = ProjectIO.copy(project);
    withoutPins.noise2D.getFirst().pinnedControls.clear();
    var a = new CompiledProject(project).noise2D("pinned", project.project.seed);
    var b = new CompiledProject(withoutPins).noise2D("pinned", project.project.seed);
    int count = 0;
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        if (a.sample(x, z) != x + 5 || b.sample(x, z) != x + 5)
          throw new AssertionError("Pin metadata changed field output");
        count += 2;
      }
    System.out.println(
        "PIN_BROWSER_ENGINE_ONLY_PASS "
            + count
            + " exact observations / canonical shared input / pin-free parity");
  }
}
