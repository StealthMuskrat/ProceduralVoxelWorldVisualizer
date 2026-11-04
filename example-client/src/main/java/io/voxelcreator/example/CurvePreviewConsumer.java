package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.EvaluationFailure;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;

public final class CurvePreviewConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var n = loaded.project().noise2D.getFirst();
    var item = n.processing.getFirst();
    var sampler = new CompiledProject(loaded.project()).noise2D("preview-recovery", 0);
    int finite = 0, failures = 0;
    for (int i = -16; i <= 16; i++)
      for (int z : new int[] {-3, 0, 7}) {
        double x = i / 16., actual = sampler.sample(x, z);
        if (!Double.isFinite(actual) || Math.abs(actual / 1e308 - x) > 2e-14)
          throw new AssertionError("Unchanged raw extension at " + x);
        finite++;
      }
    for (int x : new int[] {-4, 4})
      for (int z : new int[] {-3, 0, 7}) {
        try {
          sampler.sample(x, z);
          throw new AssertionError("Overflow was hidden");
        } catch (EvaluationFailure expected) {
          if (!n.uuid.equals(expected.resourceUuid)
              || !item.uuid.equals(expected.itemUuid)
              || expected.x != x
              || expected.z != z) throw new AssertionError("Wrong numerical boundary", expected);
          failures++;
        }
      }
    verifyReuse(Path.of(args[0]).toAbsolutePath().getParent());
    System.out.println(
        "CURVE_PREVIEW_ENGINE_ONLY_PASS "
            + finite
            + " raw finite vectors / "
            + failures
            + " unchanged numerical boundaries");
  }

  private static void verifyReuse(Path directory) throws Exception {
    var report = ProjectIO.JSON.readTree(directory.resolve("curve-preview-native.json").toFile());
    if (report.path("formatVersion").asInt() != 2 || report.path("cacheCases").size() != 4)
      throw new AssertionError("Missing curve reuse receipt");
    var cases = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
    for (var entry : report.path("cacheCases"))
      if (cases.put(entry.path("id").asText(), entry) != null)
        throw new AssertionError("Duplicate curve reuse case");
    if (!cases
        .keySet()
        .equals(java.util.Set.of("stationary", "numeric-gaps", "vertical-view", "edit-history")))
      throw new AssertionError("Incomplete curve reuse cases");
    for (String key : new String[] {"stationary", "numeric-gaps", "edit-history"})
      if (cases.get(key).path("frames").asInt() < 12)
        throw new AssertionError("Insufficient unchanged native frames");
    if (cases.get("stationary").path("compilations").asInt() != 1
        || cases.get("stationary").path("captures").asInt() != 1
        || cases.get("numeric-gaps").path("compilations").asInt() != 1
        || cases.get("numeric-gaps").path("captures").asInt() != 2
        || cases.get("numeric-gaps").path("failedSamples").asInt()
            != report.path("failedSamples").asInt()
        || cases.get("vertical-view").path("compilations").asInt() != 1
        || cases.get("vertical-view").path("captures").asLong()
            != cases.get("vertical-view").path("fitCaptures").asLong()
        || cases.get("edit-history").path("compilations").asInt() != 2
        || !cases.get("edit-history").path("privateStage").asBoolean()
        || !cases.get("edit-history").path("oneUndoRedo").asBoolean())
      throw new AssertionError("Curve reuse ownership/work receipt differs");
    com.fasterxml.jackson.databind.JsonNode edited = null, undo = null;
    int samples = 0;
    for (String state : new String[] {"edited", "undo", "redo"}) {
      var loaded = new ProjectLoader().load(directory.resolve("curve-cache-" + state + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var tree = ProjectIO.JSON.valueToTree(loaded.project());
      if (state.equals("edited")) edited = tree;
      else if (state.equals("undo")) undo = tree;
      else if (!tree.equals(edited))
        throw new AssertionError("Redo differs from the accepted curve project");
      double factor = state.equals("undo") ? 1 : 2;
      var sampler = new CompiledProject(loaded.project()).noise2D("cache-edit", 0);
      for (int i = -16; i <= 32; i++)
        for (int z : new int[] {-3, 0, 7}) {
          double x = i / 16., expected = factor * Math.max(0, Math.min(1, x));
          if (Double.doubleToRawLongBits(expected)
              != Double.doubleToRawLongBits(sampler.sample(x, z)))
            throw new AssertionError("Cached curve saved arithmetic at " + state + "/" + x);
          samples++;
        }
    }
    var restored = edited.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            restored
                .path("noise2D")
                .get(0)
                .path("processing")
                .get(0)
                .path("parameters")
                .path("curve")
                .path("points")
                .get(1))
        .put("y", 1.0);
    if (!restored.equals(undo))
      throw new AssertionError("Curve Apply changed unrelated saved values");
    System.out.println(
        "CURVE_REUSE_ENGINE_ONLY_PASS "
            + samples
            + " exact affine/clamped samples / receipts / one Undo-Redo");
  }
}
