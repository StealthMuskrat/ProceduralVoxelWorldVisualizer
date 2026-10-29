package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit portable-package I/O; the active project is never an export destination. */
final class ResourcePackageFiles {
  private static final String PROJECT_SUFFIX = ".pvproj.json", PACKAGE_SUFFIX = ".pvresources.json";

  interface Storage {
    void write(Path temporary, byte[] content) throws IOException;

    void replace(Path temporary, Path destination) throws IOException;
  }

  private final Storage storage;

  ResourcePackageFiles() {
    this(
        new Storage() {
          public void write(Path temporary, byte[] content) throws IOException {
            try (var channel =
                FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
              var buffer = ByteBuffer.wrap(content);
              while (buffer.hasRemaining()) channel.write(buffer);
              channel.force(true);
            }
          }

          public void replace(Path temporary, Path destination) throws IOException {
            // Fail without replacing the target when atomic replacement is unsupported.
            try {
              Files.move(
                  temporary,
                  destination,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
              throw new IOException(
                  "This location does not support safe package replacement; choose another"
                      + " location",
                  unsupported);
            }
          }
        });
  }

  ResourcePackageFiles(Storage storage) {
    this.storage = Objects.requireNonNull(storage);
  }

  static Path location(String text) throws IOException {
    if (text == null || text.isBlank()) throw new IOException("Enter a resource package path");
    try {
      return Path.of(text);
    } catch (InvalidPathException invalid) {
      throw new IOException("Invalid resource package path: " + invalid.getReason(), invalid);
    }
  }

  static Path suggestedLocation(Path project) {
    if (project == null) return Path.of("resources" + PACKAGE_SUFFIX);
    var filename = project.getFileName();
    if (filename == null) return project.resolve("resources" + PACKAGE_SUFFIX);
    String name = filename.toString();
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(PACKAGE_SUFFIX))
      name = name.substring(0, name.length() - PACKAGE_SUFFIX.length()) + "-export";
    else if (lower.endsWith(PROJECT_SUFFIX))
      name = name.substring(0, name.length() - PROJECT_SUFFIX.length());
    else if (lower.endsWith(".json")) name = name.substring(0, name.length() - 5);
    if (name.isBlank()) name = "resources";
    Path suggestion = project.resolveSibling(name + PACKAGE_SUFFIX);
    if (suggestion.toAbsolutePath().normalize().equals(project.toAbsolutePath().normalize()))
      suggestion = project.resolveSibling(name + "-export" + PACKAGE_SUFFIX);
    return suggestion;
  }

  ProjectData read(Path source) throws IOException {
    JsonNode tree =
        ProjectIO.JSON
            .readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readValue(Files.readString(source));
    if (tree == null || !tree.isObject())
      throw new IOException("Expected a resource package object");
    var fields = tree.fieldNames();
    while (fields.hasNext()) {
      String key = fields.next();
      if (!Set.of("packageVersion", "resources").contains(key))
        throw new IOException("Unknown package field /" + key);
    }
    var version = tree.get("packageVersion");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToInt()
        || version.intValue() != 1)
      throw new IOException("/packageVersion requires supported resource package version 1");
    var resources = tree.get("resources");
    if (resources == null || !resources.isObject())
      throw new IOException("/resources requires a complete project-format resource object");
    var loaded = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(resources));
    if (!loaded.success())
      throw new IOException("Invalid package /resources: " + summary(loaded.diagnostics()));
    return loaded.project();
  }

  void write(Path destination, ProjectData resources, Path activeProject) throws IOException {
    // Own and validate the whole payload before creating directories or touching the destination.
    var snapshot = ProjectIO.copy(Objects.requireNonNull(resources));
    var issues = NoiseCompiler.validate(snapshot);
    if (!issues.isEmpty()) throw new IOException("Invalid export resources: " + summary(issues));
    var pack = new Desktop.PortablePackage();
    pack.resources = snapshot;
    byte[] bytes = ProjectIO.JSON.writeValueAsString(pack).getBytes(StandardCharsets.UTF_8);
    Path target = Objects.requireNonNull(destination).toAbsolutePath();
    ExportPaths.rejectProjectTarget(target, activeProject, "resource package");
    Path parent = target.getParent();
    if (parent == null || target.getFileName() == null)
      throw new IOException("Choose a package file path");
    Files.createDirectories(parent);
    // Resolve real parent paths as well, including aliases when the project file is absent.
    ExportPaths.rejectProjectTarget(target, activeProject, "resource package");
    Path temporary = Files.createTempFile(parent, ".voxel-package-", ".tmp");
    Throwable failure = null;
    try {
      storage.write(temporary, bytes);
      ExportPaths.rejectProjectTarget(target, activeProject, "resource package");
      storage.replace(temporary, target);
    } catch (IOException | RuntimeException | Error rejected) {
      failure = rejected;
      throw rejected;
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanup) {
        if (failure != null) failure.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }

  private static String summary(List<Diagnostic> issues) {
    return issues.stream()
            .limit(8)
            .map(
                issue -> {
                  String text = issue.toString();
                  return text.length() > 1024 ? text.substring(0, 1023) + "…" : text;
                })
            .toList()
        + " ("
        + issues.size()
        + " issue(s))";
  }
}
