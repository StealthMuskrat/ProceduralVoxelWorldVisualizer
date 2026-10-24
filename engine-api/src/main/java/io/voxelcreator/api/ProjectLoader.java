package io.voxelcreator.api;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.io.*;
import java.nio.file.*;

public final class ProjectLoader {
  public ProjectIO.LoadResult load(Path path) throws IOException {
    var load = new ProjectIO().load(path);
    if (!load.success()) return load;
    var issues = NoiseCompiler.validate(load.project());
    return issues.isEmpty() ? load : new ProjectIO.LoadResult(null, issues);
  }
}
