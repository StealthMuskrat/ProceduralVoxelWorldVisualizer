package io.voxelcreator.editor;

import java.util.*;

/**
 * Runtime strings are captured with a current context; declared dependencies are license summaries.
 */
final class AboutDetails {
  record License(String component, String version, String terms, String scope) {}

  static final List<License> LICENSES =
      List.of(
          new License("FastNoiseLite", "1.1.1", "MIT", "Vendored engine source"),
          new License("LWJGL", "3.4.1", "BSD 3-Clause", "Native bindings"),
          new License("Dear ImGui / imgui-java", "1.89.0 binding", "MIT", "GUI"),
          new License("JOML", "1.10.8", "MIT", "Math"),
          new License("Jackson", "2.18.3", "Apache License 2.0", "Persistence"),
          new License(
              "Kotlin runtime / JetBrains annotations",
              "1.8.20 / 13.0",
              "Apache License 2.0",
              "Transitive GUI support"),
          new License("JUnit", "5.11.4", "EPL 2.0", "Build/test only"),
          new License("Gradle wrapper", "9.2.1", "Apache License 2.0", "Build only"),
          new License(
              "Eclipse Temurin",
              "25.0.4+7 when bundled",
              "GPLv2 with Classpath Exception",
              "Bundled runtime legal directory"),
          new License(
              "Mesa",
              "26.2.4 Windows development bundle",
              "Upstream component notices",
              "Conditional development software driver; docs/mesa-notices"));
  static final String OWNERSHIP =
      "No open-source license is granted for project-owned source. The repository owner's"
          + " redistribution/license notice has not been supplied.";
  static final String NOTICES =
      "Declared dependency summaries; actual runtime details are on Runtime. Full notices:"
          + " docs/LICENSES.md, docs/third-party, and bundled runtime legal directories."
          + " Conditional Mesa notices: docs/mesa-notices.";
  private final Map<String, String> runtime;

  AboutDetails(String application, String engine, Map<String, String> environment) {
    var values = new LinkedHashMap<String, String>();
    values.put("Application", "Procedural Voxel Creator " + Objects.requireNonNull(application));
    values.put("Engine", "Noise Generator Engine " + Objects.requireNonNull(engine));
    environment.forEach(
        (key, value) -> {
          if (values.containsKey(Objects.requireNonNull(key)))
            throw new IllegalArgumentException("Duplicate runtime detail " + key);
          values.put(key, value == null || value.isBlank() ? "Unavailable" : value);
        });
    runtime = Collections.unmodifiableMap(values);
  }

  Map<String, String> runtime() {
    return runtime;
  }

  String runtimeText() {
    var text = new StringBuilder();
    runtime.forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
    return text.toString();
  }

  String licenseText() {
    var text = new StringBuilder(OWNERSHIP).append("\n\n").append(NOTICES).append('\n');
    for (var license : LICENSES)
      text.append('\n')
          .append(license.component())
          .append(" / ")
          .append(license.version())
          .append(" / ")
          .append(license.terms())
          .append(" / ")
          .append(license.scope());
    return text.toString();
  }
}
