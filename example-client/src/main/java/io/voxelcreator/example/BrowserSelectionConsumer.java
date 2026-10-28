package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

/** Deferred engine-only check of the document saved after browser-only draft decisions. */
public final class BrowserSelectionConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    var compiled = new CompiledProject(p);
    double[] values = {.875, .5, .75};
    int count = 0;
    for (int i = 0; i < values.length; i++) {
      var noise = p.noise2D.get(i);
      if (!noise.id.equals(String.valueOf((char) ('a' + i))) || noise.seedOffset != 0)
        throw new AssertionError("Discarded draft escaped into saved model");
      var sampler = compiled.noise2D(noise.uuid, p.project.seed);
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) {
          if (Double.doubleToRawLongBits(sampler.sample(x, z))
              != Double.doubleToRawLongBits(values[i]))
            throw new AssertionError("Browser transaction changed field value");
          count++;
        }
    }
    if (count != 243 || p.voxelTypes.size() != 1 || !p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Saved fixture structure");
    System.out.println(
        "BROWSER_SELECTION_ENGINE_ONLY_PASS 243 exact constant values / explicit Apply / discarded"
            + " edits absent");
  }
}
