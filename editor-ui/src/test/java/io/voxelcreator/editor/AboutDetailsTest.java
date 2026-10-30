package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AboutDetailsTest {
  @Test
  void runtimeValuesAreCopiedInOrderAndMissingDetailsRemainExplicit() {
    var environment = new LinkedHashMap<String, String>();
    environment.put("Java", "fixture Java");
    environment.put("OpenGL", null);
    environment.put("OpenGL renderer", "Renderer###snow 雪\n" + "detail ".repeat(600));
    var details = new AboutDetails("app-version", "engine-version", environment);
    environment.put("Java", "changed");
    assertEquals(
        List.of("Application", "Engine", "Java", "OpenGL", "OpenGL renderer"),
        new ArrayList<>(details.runtime().keySet()));
    assertEquals("Unavailable", details.runtime().get("OpenGL"));
    assertTrue(details.runtimeText().contains("Java: fixture Java"));
    assertTrue(details.runtimeText().contains("Renderer###snow 雪\n" + "detail ".repeat(600)));
    assertThrows(UnsupportedOperationException.class, () -> details.runtime().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AboutDetails("a", "e", Map.of("Application", "override")));
  }

  @Test
  void summariesRetainConditionalBuildScopeAndDoNotGrantProjectLicensing() {
    String text = new AboutDetails("a", "e", Map.of()).licenseText();
    assertTrue(text.startsWith(AboutDetails.OWNERSHIP));
    assertTrue(text.contains("JUnit / 5.11.4 / EPL 2.0 / Build/test only"));
    assertTrue(text.contains("when bundled"));
    assertTrue(text.contains("Conditional development software driver"));
    assertTrue(text.contains("docs/third-party"));
    assertThrows(UnsupportedOperationException.class, () -> AboutDetails.LICENSES.clear());
  }
}
