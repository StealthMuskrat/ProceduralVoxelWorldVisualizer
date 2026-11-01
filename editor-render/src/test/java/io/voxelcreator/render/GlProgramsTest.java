package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class GlProgramsTest {
  private static final class Driver implements GlPrograms.Driver {
    int next = 1;
    String fail = "";
    boolean cleanupFailure;
    final Set<Integer> shaders = new LinkedHashSet<>(), programs = new LinkedHashSet<>();
    final List<Integer> deletedShaders = new ArrayList<>(), deletedPrograms = new ArrayList<>();
    final Map<Integer, String> attributes = new LinkedHashMap<>();

    public int createShader(int kind) {
      if (fail.equals("allocate-vertex") && next == 1) return 0;
      if (fail.equals("allocate-fragment") && next == 2) return 0;
      int id = next++;
      shaders.add(id);
      return id;
    }

    public void compile(int id, String source) {
      if (fail.equals("compile-throw")) throw new IllegalStateException("driver call");
    }

    public boolean compiled(int id) {
      return !fail.equals(id == 1 ? "vertex" : "fragment");
    }

    public String shaderLog(int id) {
      return "x".repeat(20000);
    }

    public int createProgram() {
      if (fail.equals("allocate-program")) return 0;
      int id = next++;
      programs.add(id);
      return id;
    }

    public void attach(int program, int shader) {
      if (fail.equals("attach")) throw new IllegalStateException("attach");
    }

    public void attribute(int program, int index, String name) {
      attributes.put(index, name);
    }

    public void link(int program) {}

    public boolean linked(int program) {
      return !fail.equals("link");
    }

    public String programLog(int program) {
      return "link failure";
    }

    public void deleteShader(int id) {
      deletedShaders.add(id);
      if (cleanupFailure && id == 1) throw new IllegalStateException("cleanup");
      assertTrue(shaders.remove(id));
    }

    public void deleteProgram(int id) {
      deletedPrograms.add(id);
      assertTrue(programs.remove(id));
    }
  }

  @Test
  void successTransfersOnlyTheLinkedProgramAndKeepsExplicitAttributeIndices() {
    var driver = new Driver();
    int program =
        GlPrograms.link(
            driver, "fixture", "vertex", "fragment", "position", null, null, null, "chunkOffset");
    assertTrue(driver.shaders.isEmpty());
    assertEquals(Set.of(program), driver.programs);
    assertEquals(List.of(1, 2), driver.deletedShaders);
    assertEquals(Map.of(0, "position", 4, "chunkOffset"), driver.attributes);
    driver.deleteProgram(program);
    assertTrue(driver.programs.isEmpty());
  }

  @Test
  void compileAttachAndLinkFailuresReleaseEveryAllocatedObjectAndBoundDriverLogs() {
    for (String stage : List.of("vertex", "fragment", "compile-throw", "attach", "link")) {
      var driver = new Driver();
      driver.fail = stage;
      var error =
          assertThrows(
              IllegalStateException.class,
              () -> GlPrograms.link(driver, "fixture", "vertex", "fragment", "position"));
      assertTrue(error.getMessage().length() <= 8192);
      assertTrue(driver.shaders.isEmpty(), stage);
      assertTrue(driver.programs.isEmpty(), stage);
      assertEquals(driver.deletedShaders.size(), new HashSet<>(driver.deletedShaders).size());
    }
  }

  @Test
  void zeroAllocationNeverTransfersAnInvalidProgramOrLeaksEarlierShaders() {
    for (String stage : List.of("allocate-vertex", "allocate-fragment", "allocate-program")) {
      var driver = new Driver();
      driver.fail = stage;
      assertThrows(
          IllegalStateException.class,
          () -> GlPrograms.link(driver, "fixture", "vertex", "fragment"));
      assertTrue(driver.shaders.isEmpty());
      assertTrue(driver.programs.isEmpty());
      assertFalse(driver.deletedShaders.contains(0));
      assertFalse(driver.deletedPrograms.contains(0));
    }
  }

  @Test
  void cleanupFailuresPreserveThePrimaryDiagnosticAndAttemptEveryRemainingRelease() {
    var driver = new Driver();
    driver.fail = "link";
    driver.cleanupFailure = true;
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> GlPrograms.link(driver, "fixture", "vertex", "fragment"));
    assertTrue(error.getMessage().contains("link failure"));
    assertEquals(1, error.getSuppressed().length);
    assertEquals(List.of(1, 2), driver.deletedShaders);
    assertEquals(1, driver.deletedPrograms.size());
    assertTrue(driver.programs.isEmpty());
  }
}
