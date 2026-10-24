package io.voxelcreator.api;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.util.*;

public final class ProjectCompiler {
  public record CompileResult(CompiledProject compiled, List<Diagnostic> diagnostics) {
    public boolean success() {
      return compiled != null;
    }
  }

  public CompileResult compileWorld(ProjectData editable) {
    var issues = NoiseCompiler.validate(editable);
    return issues.isEmpty()
        ? new CompileResult(new CompiledProject(editable), List.of())
        : new CompileResult(null, issues);
  }

  public NoiseSampler2D compileNoise2D(ProjectData editable, UUID id, long seed) {
    return new CompiledProject(editable).noise2D(id, seed);
  }

  public NoiseSampler3D compileNoise3D(ProjectData editable, UUID id, long seed) {
    return new CompiledProject(editable).noise3D(id, seed);
  }
}
