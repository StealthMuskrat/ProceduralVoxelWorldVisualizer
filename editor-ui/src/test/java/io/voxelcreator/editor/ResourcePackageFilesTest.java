package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred file-safety and strict version-1 portable-envelope contracts. */
class ResourcePackageFilesTest {
  @TempDir Path directory;

  private static void raise(Throwable failure) throws IOException {
    if (failure instanceof IOException io) throw io;
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  @Test
  void publishedPackageRetainsItsPayloadAndAReusedTemporaryPathWithoutCleanup() throws Exception {
    var target = directory.resolve("published.pvresources.json");
    var temporary = new java.util.concurrent.atomic.AtomicReference<Path>();
    var deletions = new java.util.concurrent.atomic.AtomicInteger();
    var io =
        new ResourcePackageFiles(
            new ResourcePackageFiles.Storage() {
              public void write(Path temp, byte[] bytes) throws IOException {
                Files.write(temp, bytes);
              }

              public void replace(Path temp, Path destination) throws IOException {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(temp, "new owner of old package path");
                temporary.set(temp);
              }

              public void delete(Path temp) throws IOException {
                deletions.incrementAndGet();
                throw new IOException("cleanup after publication must not run");
              }
            });
    var source = resources();
    String before = ProjectIO.JSON.writeValueAsString(source);
    io.write(target, source, null);
    assertEquals(before, ProjectIO.JSON.writeValueAsString(io.read(target)));
    assertEquals(before, ProjectIO.JSON.writeValueAsString(source));
    assertEquals("new owner of old package path", Files.readString(temporary.get()));
    assertEquals(0, deletions.get());
  }

  @Test
  void rejectedPackageWriteOrReplacementKeepsPrimaryFailureAcrossCleanupKinds() throws Exception {
    for (boolean writeFault : new boolean[] {false, true})
      for (Throwable cleanup :
          List.of(
              new IOException("cleanup IO"),
              new IllegalStateException("cleanup runtime"),
              new AssertionError("cleanup Error"))) {
        var target = directory.resolve("retained.pvresources.json");
        Files.writeString(target, "prior complete package");
        var primary = new IOException("original package failure");
        var deletions = new java.util.concurrent.atomic.AtomicInteger();
        var io =
            new ResourcePackageFiles(
                new ResourcePackageFiles.Storage() {
                  public void write(Path temp, byte[] bytes) throws IOException {
                    Files.write(temp, bytes);
                    if (writeFault) throw primary;
                  }

                  public void replace(Path temp, Path destination) throws IOException {
                    throw primary;
                  }

                  public void delete(Path temp) throws IOException {
                    deletions.incrementAndGet();
                    Files.delete(temp);
                    raise(cleanup);
                  }
                });
        var caught = assertThrows(IOException.class, () -> io.write(target, resources(), null));
        assertSame(primary, caught);
        assertArrayEquals(new Throwable[] {cleanup}, caught.getSuppressed());
        assertEquals(1, deletions.get());
        assertEquals("prior complete package", Files.readString(target));
        noTemporaryFiles();
      }
  }

  @Test
  void identicalPackageAndCleanupFaultsRemainTheOriginalFailure() throws Exception {
    for (Throwable primary :
        List.of(
            new IOException("same IO"),
            new IllegalStateException("same runtime"),
            new AssertionError("same Error"))) {
      var target = directory.resolve("same.pvresources.json");
      Files.writeString(target, "retained");
      var io =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temp, byte[] bytes) throws IOException {
                  raise(primary);
                }

                public void replace(Path temp, Path destination) {
                  fail("Replacement after write fault");
                }

                public void delete(Path temp) throws IOException {
                  Files.delete(temp);
                  raise(primary);
                }
              });
      var caught = assertThrows(Throwable.class, () -> io.write(target, resources(), null));
      assertSame(primary, caught);
      assertEquals(0, caught.getSuppressed().length);
      assertEquals("retained", Files.readString(target));
      noTemporaryFiles();
    }
  }

  private ProjectData resources() {
    var project = new ProjectData();
    var noise = new Noise();
    noise.id = "field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .625);
    project.noise2D.add(noise);
    return project;
  }

  private ObjectNode envelope() {
    var pack = new Desktop.PortablePackage();
    pack.resources = resources();
    return ProjectIO.JSON.valueToTree(pack);
  }

  private Path writeInput(String text) throws IOException {
    var file = directory.resolve("input.pvresources.json");
    Files.writeString(file, text);
    return file;
  }

  private void noTemporaryFiles() throws IOException {
    try (var files = Files.walk(directory)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-package-")));
    }
  }

  @Test
  void packageSuggestionsNeverDefaultToTheProjectAndPreserveItsDirectory() {
    assertEquals(
        Path.of("resources.pvresources.json"), ResourcePackageFiles.suggestedLocation(null));
    for (var name :
        List.of("terrain.pvproj.json", "terrain.PVPROJ.JSON", "terrain.json", "terrain")) {
      var project = directory.resolve(name);
      var suggested = ResourcePackageFiles.suggestedLocation(project);
      assertEquals(directory.resolve("terrain.pvresources.json"), suggested);
      assertNotEquals(project, suggested);
    }
    var unusual = directory.resolve("terrain.pvresources.json");
    assertNotEquals(unusual, ResourcePackageFiles.suggestedLocation(unusual));
    assertThrows(IOException.class, () -> ResourcePackageFiles.location("  "));
    assertThrows(IOException.class, () -> ResourcePackageFiles.location("bad\u0000path"));
  }

  @Test
  void exportAtomicallyReplacesACompletePackageAndRoundTripsOwnedResources() throws Exception {
    var io = new ResourcePackageFiles();
    var target = directory.resolve("nested/field.pvresources.json");
    var source = resources();
    var original = ProjectIO.JSON.valueToTree(source);
    io.write(target, source, directory.resolve("project.pvproj.json"));
    var restored = io.read(target);
    assertEquals(original, ProjectIO.JSON.valueToTree(restored));
    source.noise2D.getFirst().id = "changed";
    assertEquals("field", restored.noise2D.getFirst().id);
    io.write(target, source, null);
    assertEquals("changed", io.read(target).noise2D.getFirst().id);
    assertEquals(
        1, ProjectIO.JSON.readTree(Files.readString(target)).path("packageVersion").asInt());
    noTemporaryFiles();
  }

  @Test
  void exportingToTheActiveProjectOrItsNormalizedPathRejectsBeforeAnyWrite() throws Exception {
    var target = directory.resolve("project.pvproj.json");
    new ProjectIO().save(target, resources());
    var before = Files.readAllBytes(target);
    var io = new ResourcePackageFiles();
    assertThrows(IOException.class, () -> io.write(target, resources(), target));
    assertThrows(
        IOException.class,
        () -> io.write(directory.resolve("./project.pvproj.json"), resources(), target));
    assertArrayEquals(before, Files.readAllBytes(target));
    assertTrue(new ProjectIO().load(target).success());
    noTemporaryFiles();
  }

  @Test
  void hardLinkAliasesOfTheActiveProjectRejectWithoutChangingEitherFile() throws Exception {
    var project = directory.resolve("project.json");
    new ProjectIO().save(project, resources());
    var alias = directory.resolve("alias.pvresources.json");
    try {
      Files.createLink(alias, project);
    } catch (IOException | UnsupportedOperationException unsupported) {
      assumeTrue(false, "Hard links unavailable in this environment");
    }
    byte[] before = Files.readAllBytes(project);
    assertThrows(
        IOException.class, () -> new ResourcePackageFiles().write(alias, resources(), project));
    assertArrayEquals(before, Files.readAllBytes(project));
    assertArrayEquals(before, Files.readAllBytes(alias));
    noTemporaryFiles();
  }

  @Test
  void symbolicDirectoryAndDanglingProjectAliasesCannotBypassProtection() throws Exception {
    var real = Files.createDirectory(directory.resolve("real"));
    var alias = directory.resolve("alias");
    var projectLink = directory.resolve("project-link.json");
    var target = real.resolve("project.json");
    try {
      Files.createSymbolicLink(alias, real);
      Files.createSymbolicLink(projectLink, target);
    } catch (IOException | UnsupportedOperationException unsupported) {
      assumeTrue(false, "Symbolic links unavailable in this environment");
    }
    var io = new ResourcePackageFiles();
    // Both project and target may be absent: real-parent and dangling-leaf aliases still protect
    // them.
    assertThrows(
        IOException.class, () -> io.write(alias.resolve("project.json"), resources(), target));
    assertThrows(IOException.class, () -> io.write(target, resources(), projectLink));
    assertFalse(Files.exists(target));
    new ProjectIO().save(target, resources());
    byte[] before = Files.readAllBytes(target);
    assertThrows(
        IOException.class, () -> io.write(alias.resolve("project.json"), resources(), projectLink));
    assertArrayEquals(before, Files.readAllBytes(target));
    noTemporaryFiles();
  }

  @Test
  void rejectedResourcesNeverTouchThePreviousDestinationOrCreateParents() throws Exception {
    var invalid = resources();
    invalid.noise2D.getFirst().id = " invalid ";
    var old = directory.resolve("old.pvresources.json");
    Files.writeString(old, "previous valid destination");
    var io = new ResourcePackageFiles();
    assertThrows(IOException.class, () -> io.write(old, invalid, null));
    assertEquals("previous valid destination", Files.readString(old));
    var missing = directory.resolve("missing/new/package.json");
    assertThrows(IOException.class, () -> io.write(missing, invalid, null));
    assertFalse(Files.exists(directory.resolve("missing")));
    noTemporaryFiles();
  }

  @Test
  void partialStagingAndReplacementFailuresPreserveTheTargetAndAllowRetry() throws Exception {
    var target = directory.resolve("package.json");
    Files.writeString(target, "old package");
    for (boolean duringWrite : new boolean[] {true, false}) {
      var io =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temporary, byte[] content) throws IOException {
                  Files.write(temporary, duringWrite ? Arrays.copyOf(content, 12) : content);
                  if (duringWrite) throw new IOException("Injected partial write failure");
                }

                public void replace(Path temporary, Path destination) throws IOException {
                  throw new AtomicMoveNotSupportedException(
                      temporary.toString(),
                      destination.toString(),
                      "Injected unsupported replacement");
                }
              });
      assertThrows(IOException.class, () -> io.write(target, resources(), null));
      assertEquals("old package", Files.readString(target));
      noTemporaryFiles();
    }
    new ResourcePackageFiles().write(target, resources(), null);
    assertEquals("field", new ResourcePackageFiles().read(target).noise2D.getFirst().id);
  }

  @Test
  void missingWrongUnknownDuplicateAndTrailingEnvelopeFieldsAreRejected() throws Exception {
    var io = new ResourcePackageFiles();
    for (String text :
        List.of(
            "{}",
            "[]",
            "null",
            "{\"packageVersion\":1}",
            "{\"resources\":{}}",
            "{\"packageVersion\":1,\"packageVersion\":1,\"resources\":{}}")) {
      var path = writeInput(text);
      assertThrows(IOException.class, () -> io.read(path));
    }
    for (var version : List.of("null", "0", "2", "1.0", "\"1\"", "4294967297")) {
      var tree = envelope();
      tree.set("packageVersion", ProjectIO.JSON.readTree(version));
      assertThrows(IOException.class, () -> io.read(writeInput(tree.toString())));
    }
    var unknown = envelope();
    unknown.put("unknown", true);
    assertThrows(IOException.class, () -> io.read(writeInput(unknown.toString())));
    assertThrows(IOException.class, () -> io.read(writeInput(envelope() + " {}")));
  }

  @Test
  void incompleteResourceProjectsCannotBeFilledFromJavaDefaults() throws Exception {
    var io = new ResourcePackageFiles();
    var tree = envelope();
    ((ObjectNode) tree.get("resources")).remove("project");
    var failure = assertThrows(IOException.class, () -> io.read(writeInput(tree.toString())));
    assertTrue(failure.getMessage().contains("/resources"));
    assertTrue(failure.getMessage().contains("/project"));
    assertThrows(
        IOException.class,
        () -> io.read(writeInput(ProjectIO.JSON.writeValueAsString(resources()))));
  }
}
