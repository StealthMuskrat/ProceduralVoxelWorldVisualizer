package io.voxelcreator.api;

import io.voxelcreator.model.*;
import java.util.*;

public final class ProjectCompiler {
  /** Diagnostic ownership is independent of caller lists and retained compiled snapshots. */
  public record CompileResult(CompiledProject compiled, List<Diagnostic> diagnostics) {
    public CompileResult {
      diagnostics = List.copyOf(diagnostics);
    }

    public boolean success() {
      return compiled != null;
    }
  }

  public CompileResult compileWorld(ProjectData editable) {
    var prepared = CompiledProject.prepare(editable);
    var issues = prepared.diagnostics();
    return issues.isEmpty()
        ? new CompileResult(CompiledProject.accept(prepared), List.of())
        : new CompileResult(null, issues);
  }

  public NoiseSampler2D compileNoise2D(ProjectData editable, UUID id, long seed) {
    return new CompiledProject(editable).noise2D(id, seed);
  }

  public NoiseSampler3D compileNoise3D(ProjectData editable, UUID id, long seed) {
    return new CompiledProject(editable).noise3D(id, seed);
  }
}
