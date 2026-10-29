package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred bounded configuration publication and injected failure contracts. */
class PreferenceFilesTest {
  @TempDir Path directory;

  private void noTemporaryFiles() throws IOException {
    try (var files = Files.walk(directory)) {
      assertTrue(
          files.noneMatch(
              p -> p.getFileName().toString().matches("\\.(user|app)-config-.*\\.tmp")));
    }
  }

  @Test
  void explicitUserAndApplicationWritesRoundTripWithoutSerializingStorage() throws Exception {
    var user = new UserConfig();
    user.workspace.width = 1234;
    var file = directory.resolve("nested/user.json");
    user.save(file);
    assertEquals(1234, UserConfig.read(file).workspace.width);
    assertFalse(ProjectIO.JSON.readTree(file.toFile()).has("files"));
    var metadata = AppMetadata.defaults();
    metadata.controls.get("noise").label = "Authored label";
    var profile = directory.resolve("nested/app.json");
    metadata.save(profile);
    assertEquals("Authored label", AppMetadata.read(profile).controls.get("noise").label);
    noTemporaryFiles();
  }

  @Test
  void byteBoundRejectsSerializedOversizeBeforeCreatingDestinationDirectories() {
    var file = directory.resolve("must-not-exist/config.json");
    var large = ProjectIO.JSON.getNodeFactory().textNode("x".repeat(PreferenceFiles.MAX_BYTES));
    assertThrows(
        IOException.class, () -> new PreferenceFiles().write(file, large, ".user-config-"));
    assertFalse(Files.exists(file.getParent()));
  }

  @Test
  void boundedReaderStopsAtTheFirstExcessByteEvenWhenAStreamKeepsGrowing() throws Exception {
    var reads = new AtomicInteger();
    var growing =
        new InputStream() {
          @Override
          public int read() {
            reads.incrementAndGet();
            return ' ';
          }

          @Override
          public int read(byte[] bytes, int offset, int length) {
            Arrays.fill(bytes, offset, offset + length, (byte) ' ');
            reads.addAndGet(length);
            return length;
          }
        };
    assertThrows(IOException.class, () -> PreferenceFiles.read(growing));
    assertEquals(PreferenceFiles.MAX_BYTES + 1, reads.get());
    assertEquals(
        PreferenceFiles.MAX_BYTES,
        PreferenceFiles.read(new ByteArrayInputStream(new byte[PreferenceFiles.MAX_BYTES])).length);
  }

  @Test
  void failedPartialWritePreservesDestinationAndDeletesOnlyItsTemporaryFile() throws Exception {
    var target = directory.resolve("config.json");
    Files.writeString(target, "prior complete file");
    var writer =
        new PreferenceFiles(
            new PreferenceFiles.Storage() {
              public void write(Path temp, byte[] bytes) throws IOException {
                Files.writeString(temp, "partial");
                throw new IOException("write fixture");
              }

              public void replace(Path temp, Path destination) {
                fail("Replacement after failed write");
              }
            });
    assertThrows(IOException.class, () -> writer.write(target, new UserConfig(), ".user-config-"));
    assertEquals("prior complete file", Files.readString(target));
    noTemporaryFiles();
  }

  @Test
  void unsupportedReplacementCannotPublishThemeBindingsTrustOrMetadataAndNeverFallsBack()
      throws Exception {
    var target = directory.resolve("config.json");
    Files.writeString(target, "prior complete file");
    var calls = new AtomicInteger();
    var writer =
        new PreferenceFiles(
            new PreferenceFiles.Storage() {
              public void write(Path temp, byte[] bytes) throws IOException {
                Files.write(temp, bytes);
              }

              public void replace(Path temp, Path destination) throws IOException {
                calls.incrementAndGet();
                assertTrue(Files.size(temp) > 0);
                throw new AtomicMoveNotSupportedException(
                    temp.toString(), destination.toString(), "fixture");
              }
            });
    var user = new UserConfig(writer);
    var theme = ThemePalette.from(user.appearance);
    var bindings = user.input.shortcuts;
    var trust = user.performance.trustedOrigins;
    var next = new LinkedHashMap<>(bindings);
    next.put("Profiler", "Alt+F9");
    assertThrows(
        IllegalStateException.class, () -> user.applyTheme(ThemePalette.starting("Light"), target));
    assertThrows(IllegalStateException.class, () -> user.applyShortcuts(next, target));
    assertThrows(
        IllegalStateException.class,
        () ->
            user.applyUpdateTrust(
                "https://updates.example.test/meta.json",
                List.of("https://updates.example.test"),
                target));
    assertThrows(IllegalStateException.class, () -> user.save(target));
    assertThrows(IllegalStateException.class, () -> AppMetadata.defaults().save(target, writer));
    assertEquals(5, calls.get());
    assertEquals(theme, ThemePalette.from(user.appearance));
    assertSame(bindings, user.input.shortcuts);
    assertSame(trust, user.performance.trustedOrigins);
    assertEquals("", user.performance.updateEndpoint);
    assertEquals("prior complete file", Files.readString(target));
    noTemporaryFiles();
  }

  @Test
  void cleanupFailureIsSuppressedUnderTheOriginalPublicationFailure() throws Exception {
    var failure = new IOException("publication fixture");
    var cleanup = new IOException("cleanup fixture");
    var writer =
        new PreferenceFiles(
            new PreferenceFiles.Storage() {
              public void write(Path temp, byte[] bytes) throws IOException {
                Files.write(temp, bytes);
              }

              public void replace(Path temp, Path target) throws IOException {
                throw failure;
              }

              public void delete(Path temp) throws IOException {
                Files.deleteIfExists(temp);
                throw cleanup;
              }
            });
    var caught =
        assertThrows(
            IOException.class,
            () ->
                writer.write(directory.resolve("config.json"), new UserConfig(), ".user-config-"));
    assertSame(failure, caught);
    assertArrayEquals(new Throwable[] {cleanup}, caught.getSuppressed());
    noTemporaryFiles();
  }

  @Test
  void runtimeFailureAndRootPathCannotReplaceSavedContentOrLeakTemporaryFiles() throws Exception {
    var target = directory.resolve("config.json");
    Files.writeString(target, "retained");
    var writer =
        new PreferenceFiles(
            new PreferenceFiles.Storage() {
              public void write(Path temp, byte[] bytes) {
                throw new IllegalStateException("runtime fixture");
              }

              public void replace(Path temp, Path destination) {
                fail("Replacement after runtime failure");
              }
            });
    assertThrows(
        IllegalStateException.class, () -> writer.write(target, new UserConfig(), ".user-config-"));
    assertThrows(
        IOException.class, () -> writer.write(target.getRoot(), new UserConfig(), ".user-config-"));
    assertEquals("retained", Files.readString(target));
    noTemporaryFiles();
  }

  @Test
  void readersRejectOversizeFilesAndOverflowingApplicationVersionWithoutRewriting()
      throws Exception {
    var target = directory.resolve("oversized.json");
    byte[] bytes = new byte[PreferenceFiles.MAX_BYTES + 1];
    Arrays.fill(bytes, (byte) ' ');
    Files.write(target, bytes);
    assertThrows(IOException.class, () -> UserConfig.read(target));
    assertThrows(IOException.class, () -> AppMetadata.read(target));
    assertEquals(bytes.length, Files.size(target));
    Files.writeString(target, "{\"formatVersion\":4294967297}");
    assertThrows(IllegalArgumentException.class, () -> AppMetadata.read(target));
    assertEquals("{\"formatVersion\":4294967297}", Files.readString(target));
  }
}
