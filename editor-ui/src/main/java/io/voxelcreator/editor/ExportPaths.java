package io.voxelcreator.editor;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayDeque;

/** Shared editor export guard, including absent paths beneath aliased parents. */
final class ExportPaths {
  private ExportPaths() {}

  static void rejectProjectTarget(Path target, Path project, String kind) throws IOException {
    if (project == null) return;
    Path active = project.toAbsolutePath();
    if (target.normalize().equals(active.normalize())
        || Files.exists(target) && Files.exists(active) && Files.isSameFile(target, active)
        || realLocation(target).equals(realLocation(active)))
      throw new IOException(
          "Export cannot replace the active project file; choose a " + kind + " path");
  }

  private static Path realLocation(Path path) throws IOException {
    return realLocation(path.toAbsolutePath(), 0);
  }

  private static Path realLocation(Path path, int links) throws IOException {
    if (links > 40) throw new IOException("Too many symbolic links in export or project path");
    var tail = new ArrayDeque<Path>();
    Path ancestor = path;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      if (ancestor.getFileName() != null) tail.addFirst(ancestor.getFileName());
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) return path.normalize();
    Path real;
    if (Files.isSymbolicLink(ancestor)) {
      var link = Files.readSymbolicLink(ancestor);
      real = realLocation(link.isAbsolute() ? link : ancestor.getParent().resolve(link), links + 1);
    } else real = ancestor.toRealPath();
    for (var part : tail) real = real.resolve(part);
    return real.normalize();
  }
}
