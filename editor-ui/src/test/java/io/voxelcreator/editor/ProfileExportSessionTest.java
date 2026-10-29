package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred reviewed-capture publication, retry and document-isolation contracts. */
class ProfileExportSessionTest {
  @TempDir Path directory;

  private Profiler.Snapshot snapshot() {
    try (var profiler = new Profiler()) {
      var capture = profiler.start(Profiler.Options.defaults());
      profiler
          .scope(7, null, null)
          .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 123, "reviewed");
      profiler.stop();
      return capture.snapshot();
    }
  }

  private void noTemporaryFiles() throws IOException {
    try (var files = Files.walk(directory)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".profile-")));
    }
  }

  @Test
  void liveGrowthReplacementAndClearCannotRetargetAReviewedSnapshot() throws Exception {
    try (var profiler = new Profiler()) {
      var capture = profiler.start(Profiler.Options.defaults());
      var scope = profiler.scope(1, null, null);
      scope.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 12, "opening");
      var frozen = capture.snapshot();
      var review = new ProfileExportSession(frozen);
      scope.gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 999, "later");
      var replacement = profiler.start(Profiler.Options.defaults());
      profiler
          .scope(2, null, null)
          .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 777, "replacement");
      profiler.clear();
      Path file = directory.resolve("frozen.json");
      review.write(file.toString(), false, null);
      var json = ProjectIO.JSON.readTree(file.toFile());
      assertSame(frozen, review.snapshot());
      assertNotEquals(replacement.snapshot().captureId(), frozen.captureId());
      assertEquals(frozen.captureId().toString(), json.path("captureId").asText());
      assertEquals(1, json.path("samples").size());
      assertEquals(12, json.path("samples").get(0).path("value").asLong());
      assertEquals("RECORDING", json.path("state").asText());
    }
  }

  @Test
  void failedReplacementKeepsExactReviewForCorrectedPathRetry() throws Exception {
    var frozen = snapshot();
    var review = new ProfileExportSession(frozen);
    Path occupied = directory.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "retained");
    assertThrows(IOException.class, () -> review.write(occupied.toString(), false, null));
    assertTrue(review.available());
    assertSame(frozen, review.snapshot());
    assertEquals("retained", Files.readString(occupied.resolve("keep")));
    noTemporaryFiles();
    Path target = directory.resolve("nested/retry.json");
    assertEquals(target, review.write(target.toString(), false, null));
    assertEquals(
        frozen.captureId().toString(),
        ProjectIO.JSON.readTree(target.toFile()).path("captureId").asText());
    assertFalse(review.available());
    noTemporaryFiles();
  }

  @Test
  void invalidPathsKeepReviewWithoutInvokingWriter() throws Exception {
    var calls = new AtomicInteger();
    var review = new ProfileExportSession(snapshot(), (p, s, c) -> calls.incrementAndGet());
    for (String text : new String[] {null, "", "   ", "bad\u0000path"}) {
      assertThrows(IOException.class, () -> review.write(text, false, null));
      assertTrue(review.available());
    }
    assertEquals(0, calls.get());
  }

  @Test
  void activeProjectAndNormalizedAliasAreProtectedForBothFormats() throws Exception {
    Path project = directory.resolve("project.json");
    Files.writeString(project, "saved project");
    var review = new ProfileExportSession(snapshot());
    for (boolean csv : new boolean[] {false, true}) {
      assertThrows(IOException.class, () -> review.write(project.toString(), csv, project));
      assertThrows(
          IOException.class,
          () -> review.write(directory.resolve("./project.json").toString(), csv, project));
      assertTrue(review.available());
    }
    assertEquals("saved project", Files.readString(project));
    noTemporaryFiles();
  }

  @Test
  void hardLinkAliasCannotReplaceTheActiveProject() throws Exception {
    Path project = directory.resolve("project.json"), alias = directory.resolve("alias.csv");
    Files.writeString(project, "saved project");
    try {
      Files.createLink(alias, project);
    } catch (IOException | UnsupportedOperationException unsupported) {
      assumeTrue(false, "Hard links unavailable");
    }
    var review = new ProfileExportSession(snapshot());
    assertThrows(IOException.class, () -> review.write(alias.toString(), true, project));
    assertEquals("saved project", Files.readString(alias));
    assertTrue(review.available());
    noTemporaryFiles();
  }

  @Test
  void symbolicParentAndDanglingAliasesProtectAbsentProjectLocation() throws Exception {
    Path real = directory.resolve("real"), alias = directory.resolve("alias");
    Files.createDirectory(real);
    try {
      Files.createSymbolicLink(alias, real);
    } catch (IOException | UnsupportedOperationException unsupported) {
      assumeTrue(false, "Symbolic links unavailable");
    }
    Path project = real.resolve("absent.json"), dangling = directory.resolve("dangling.json");
    Files.createSymbolicLink(dangling, project);
    var review = new ProfileExportSession(snapshot());
    assertThrows(
        IOException.class,
        () -> review.write(alias.resolve("absent.json").toString(), false, project));
    assertThrows(IOException.class, () -> review.write(dangling.toString(), true, project));
    assertFalse(Files.exists(project));
    assertTrue(Files.isSymbolicLink(dangling));
    assertTrue(review.available());
    noTemporaryFiles();
  }

  @Test
  void publicationChecksTheLatestActiveProjectPathAtEachAttempt() throws Exception {
    Path first = directory.resolve("first.json"), second = directory.resolve("second.json");
    Files.writeString(second, "latest project");
    var review = new ProfileExportSession(snapshot());
    assertThrows(IOException.class, () -> review.write(second.toString(), false, second));
    review.write(first.toString(), false, second);
    assertEquals("latest project", Files.readString(second));
    assertEquals(
        123, ProjectIO.JSON.readTree(first.toFile()).path("samples").get(0).path("value").asLong());
  }

  @Test
  void acceptedAndCancelledReviewsCannotBeReusedOrReentered() throws Exception {
    var calls = new AtomicInteger();
    var review = new ProfileExportSession(snapshot(), (p, s, c) -> calls.incrementAndGet());
    review.write(directory.resolve("one").toString(), false, null);
    assertThrows(
        IOException.class, () -> review.write(directory.resolve("two").toString(), true, null));
    review.cancel();
    var cancelled = new ProfileExportSession(snapshot(), (p, s, c) -> calls.incrementAndGet());
    cancelled.cancel();
    assertThrows(IOException.class, () -> cancelled.write("ignored", false, null));
    var owner = new ProfileExportSession[1];
    owner[0] =
        new ProfileExportSession(
            snapshot(),
            (p, s, c) -> {
              assertFalse(owner[0].available());
              assertThrows(IOException.class, () -> owner[0].write("reentrant", false, null));
              assertThrows(IllegalStateException.class, owner[0]::cancel);
              calls.incrementAndGet();
            });
    owner[0].write("outer", true, null);
    assertEquals(2, calls.get());
  }

  @Test
  void runtimeWriterFailureRetainsReviewAndCsvUsesTheOwnedMeasurements() throws Exception {
    var frozen = snapshot();
    var calls = new AtomicInteger();
    var review =
        new ProfileExportSession(
            frozen,
            (p, s, csv) -> {
              assertSame(frozen, s);
              assertTrue(csv);
              if (calls.incrementAndGet() == 1) throw new IllegalStateException();
              ProfileIO.writeCSV(p, s);
            });
    var file = directory.resolve("capture.csv");
    assertThrows(IllegalStateException.class, () -> review.write(file.toString(), true, null));
    assertTrue(review.available());
    review.write(file.toString(), true, null);
    var csv = Files.readString(file);
    assertTrue(csv.contains(frozen.captureId() + ",STOPPED,"));
    assertTrue(csv.contains(",reviewed,"));
    assertTrue(csv.contains(",123,COUNT,"));
    assertEquals(2, calls.get());
  }

  @Test
  void captureReviewAndExportPreserveProjectHistoryPrivateSeedAndPreferences() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "field";
                noise.algorithm.key = "CONSTANT";
                noise.algorithm.parameters.clear();
                noise.algorithm.parameters.put("value", .125);
                p.noise2D.add(noise);
              }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (Noise) state.draft;
      draft.id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var project = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      long revision = state.revision, history = state.document.historyBytes();
      var review = new ProfileExportSession(snapshot());
      review.write(directory.resolve("capture.json").toString(), false, state.document.path());
      assertSame(draft, state.draft);
      assertEquals("private", draft.id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }
}
