package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred malformed-candidate reporting without retraversal or mutable diagnostic ownership. */
class ValidationFailureOwnershipTest {
  @TempDir Path directory;

  private static List<Noise> broken(RuntimeException failure, AtomicInteger accesses) {
    return new AbstractList<>() {
      public int size() { accesses.incrementAndGet(); throw failure; }
      public Noise get(int index) { throw new AssertionError("Unexpected element read"); }
    };
  }
  private static RuntimeException failure(String mode, AtomicInteger reads) {
    return new RuntimeException() {
      public String getMessage() {
        int read = reads.incrementAndGet(); return switch (mode) {
          case "RUNTIME" -> throw new IllegalStateException("reader"); case "ERROR" -> throw new AssertionError("reader");
          case "NULL" -> null; case "BLANK" -> " \t"; case "LONG" -> "雪".repeat(3000);
          case "ASTRAL" -> "😀".repeat(2000); case "LIMIT" -> "😀".repeat(1024); default -> "message-" + read;
        };
      }
      public String toString() { throw new AssertionError("Host toString called"); }
    };
  }

  @Test
  void structuralRuntimeFaultIsDescribedOnceWithoutRetryingCandidateEnrichment() throws Exception {
    for (String mode : List.of("MUTABLE", "RUNTIME", "ERROR", "NULL", "BLANK", "LONG", "ASTRAL", "LIMIT")) {
      var project = new ProjectData(); String authored = ProjectIO.JSON.writeValueAsString(project);
      var accesses = new AtomicInteger(); var reads = new AtomicInteger(); var failure = failure(mode, reads); project.noise2D = broken(failure, accesses);
      var issues = Validator.validate(project);
      String expected = switch (mode) {
        case "LONG" -> "雪".repeat(2047) + "…"; case "ASTRAL" -> "😀".repeat(1023) + "…";
        case "LIMIT" -> "😀".repeat(1024); case "MUTABLE" -> "message-1"; default -> "Null or malformed value";
      };
      assertEquals(List.of(Diagnostic.error("INVALID_STRUCTURE", "/", expected)), issues);
      assertEquals(1, accesses.get()); assertEquals(1, reads.get()); assertEquals(0, failure.getSuppressed().length);
      assertThrows(UnsupportedOperationException.class, issues::clear);
      project.noise2D = new ArrayList<>(); assertTrue(Validator.validate(project).isEmpty());
      assertEquals(authored, ProjectIO.JSON.writeValueAsString(project)); assertEquals(expected, issues.getFirst().message()); assertEquals(1, reads.get());
    }
  }

  @Test
  void partialDiagnosticsKeepOrderPathsAndFrozenTextAfterCandidateRepair() {
    var project = new ProjectData(); project.project.minY = 1; project.project.maxY = 0;
    var accesses = new AtomicInteger(); var reads = new AtomicInteger(); project.noise2D = broken(failure("MUTABLE", reads), accesses);
    var issues = Validator.validate(project);
    assertEquals(List.of("INVALID_BOUNDS", "INVALID_STRUCTURE"), issues.stream().map(Diagnostic::code).toList());
    assertEquals(List.of("/project", "/"), issues.stream().map(Diagnostic::jsonPointer).toList());
    assertEquals("message-1", issues.getLast().message()); assertEquals(1, accesses.get()); assertEquals(1, reads.get());
    project.project.maxY = 2; project.noise2D = new ArrayList<>(); assertTrue(Validator.validate(project).isEmpty()); assertEquals(2, issues.size());
  }

  @Test
  void messageReaderCanValidateAnIndependentCandidateAndDirectErrorsRemainDirect() {
    var accesses = new AtomicInteger(); var reads = new AtomicInteger(); var project = new ProjectData();
    var failure = new RuntimeException() { public String getMessage() { reads.incrementAndGet(); assertTrue(Validator.validate(new ProjectData()).isEmpty()); return "nested validation"; } };
    project.noise2D = broken(failure, accesses);
    assertEquals("nested validation", Validator.validate(project).getFirst().message()); assertEquals(1, reads.get()); assertEquals(1, accesses.get());
    var error = new AssertionError("direct traversal Error");
    project.noise2D = new AbstractList<>() { public int size() { throw error; } public Noise get(int index) { throw error; } };
    assertSame(error, assertThrows(AssertionError.class, () -> Validator.validate(project)));
  }

  @Test
  void rejectedSaveDoesNotSerializeTheBrokenCandidateOrTouchPriorBytes() throws Exception {
    for (String mode : List.of("RUNTIME", "ERROR")) {
      var target = directory.resolve(mode + ".json"); Files.writeString(target, "prior complete document");
      var project = new ProjectData(); var accesses = new AtomicInteger(); var reads = new AtomicInteger(); project.noise2D = broken(failure(mode, reads), accesses);
      var rejection = assertThrows(IOException.class, () -> new ProjectIO().save(target, project));
      assertTrue(rejection.getMessage().contains("INVALID_STRUCTURE /: Null or malformed value"));
      assertEquals("prior complete document", Files.readString(target)); assertEquals(1, accesses.get()); assertEquals(1, reads.get());
    }
    try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
  }

  @Test
  void loadResultsDetachDiagnosticListsWithoutFreezingTheirEditableProject() {
    var issue = Diagnostic.error("INVALID_STRUCTURE", "/", "original"); var source = new ArrayList<>(List.of(issue));
    var failed = new ProjectIO.LoadResult(null, source); source.clear(); assertFalse(failed.success()); assertEquals(List.of(issue), failed.diagnostics());
    assertThrows(UnsupportedOperationException.class, () -> failed.diagnostics().add(issue));
    var project = new ProjectData(); var empty = new ArrayList<Diagnostic>(); var loaded = new ProjectIO.LoadResult(project, empty); empty.add(issue);
    assertTrue(loaded.success()); assertTrue(loaded.diagnostics().isEmpty()); assertSame(project, loaded.project()); project.project.seed = 29; assertEquals(29, loaded.project().project.seed);
    assertThrows(NullPointerException.class, () -> new ProjectIO.LoadResult(project, null));
    assertThrows(NullPointerException.class, () -> new ProjectIO.LoadResult(null, Arrays.asList((Diagnostic) null)));
  }
}
