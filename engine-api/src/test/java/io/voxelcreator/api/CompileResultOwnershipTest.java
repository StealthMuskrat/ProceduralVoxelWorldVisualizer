package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred malformed validation through public compilation and immutable result diagnostics. */
class CompileResultOwnershipTest {
  @Test
  void malformedCandidateRejectsOnceAndARepairedCandidateCompilesWithoutChangingAnOlderSnapshot() throws Exception {
    var project = new ProjectData(); String authored = ProjectIO.JSON.writeValueAsString(project); var before = new CompiledProject(project);
    var accesses = new AtomicInteger(); var reads = new AtomicInteger();
    var failure = new RuntimeException() { public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); } };
    project.noise2D = new AbstractList<>() {
      public int size() { accesses.incrementAndGet(); throw failure; }
      public Noise get(int index) { throw new AssertionError("Unexpected element read"); }
    };
    var result = new ProjectCompiler().compileWorld(project);
    assertFalse(result.success()); assertNull(result.compiled()); assertEquals(List.of(Diagnostic.error("INVALID_STRUCTURE", "/", "Null or malformed value")), result.diagnostics());
    assertEquals(1, accesses.get()); assertEquals(1, reads.get()); assertEquals(0, failure.getSuppressed().length);
    var retained = new ArrayList<VoxelChunk>();
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20)) {
      try (var world = engine.openWorld(before, new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) { retained.add(world.generateChunk(new ChunkPos(-1, 0, 1))); }
      project.noise2D = new ArrayList<>(); var repaired = new ProjectCompiler().compileWorld(project); assertTrue(repaired.success());
      assertEquals(authored, ProjectIO.JSON.writeValueAsString(project)); assertEquals(1, accesses.get()); assertEquals(1, reads.get());
      try (var world = engine.openWorld(repaired.compiled(), new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) { retained.add(world.generateChunk(new ChunkPos(-1, 0, 1))); }
    }
    for (var chunk : retained) for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) assertEquals(0, chunk.getVoxelTypeId(x, y, z));
  }

  @Test
  void compileResultsDetachDiagnosticListsAndRejectNullDiagnosticOwnership() {
    var issue = Diagnostic.error("INVALID_NOISE", "/noise", "original"); var source = new ArrayList<>(List.of(issue));
    var failed = new ProjectCompiler.CompileResult(null, source); source.clear(); assertFalse(failed.success()); assertEquals(List.of(issue), failed.diagnostics());
    assertThrows(UnsupportedOperationException.class, failed.diagnostics()::clear);
    var compiled = new CompiledProject(new ProjectData()); var empty = new ArrayList<Diagnostic>(); var success = new ProjectCompiler.CompileResult(compiled, empty); empty.add(issue);
    assertTrue(success.success()); assertSame(compiled, success.compiled()); assertTrue(success.diagnostics().isEmpty());
    assertThrows(NullPointerException.class, () -> new ProjectCompiler.CompileResult(compiled, null));
    assertThrows(NullPointerException.class, () -> new ProjectCompiler.CompileResult(null, Arrays.asList((Diagnostic) null)));
  }
}
