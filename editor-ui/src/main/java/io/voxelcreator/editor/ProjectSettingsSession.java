package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;

/** Three private raw fields, bound to one exact document revision until Apply or Cancel. */
final class ProjectSettingsSession {
  static final List<String> FIELDS = List.of("seed", "minY", "maxY");
  static final int MAX_CHARACTERS = 128;
  private final ProjectData project;
  private final long revision;
  private final long baseSeed;
  private final int baseMinY, baseMaxY;
  private final Map<String, String> text = new LinkedHashMap<>();
  private List<Diagnostic> cached, applyIssues = List.of();
  private boolean closed;

  ProjectSettingsSession(EditorState state) {
    project = state.project();
    revision = state.revision;
    baseSeed = project.project.seed;
    baseMinY = project.project.minY;
    baseMaxY = project.project.maxY;
    text.put("seed", Long.toString(project.project.seed));
    text.put("minY", Integer.toString(project.project.minY));
    text.put("maxY", Integer.toString(project.project.maxY));
  }

  String text(String field) {
    if (!FIELDS.contains(field))
      throw new IllegalArgumentException("Unknown project setting " + field);
    return text.get(field);
  }

  void edit(String field, String value) {
    if (closed) throw new IllegalStateException("Project Settings is closed");
    text(field);
    if (Objects.requireNonNull(value).length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Project setting text limit " + MAX_CHARACTERS);
    text.put(field, Objects.requireNonNull(value));
    cached = null;
    applyIssues = List.of();
  }

  boolean current(EditorState state) {
    return !closed
        && state.project() == project
        && state.revision == revision
        && project.project.seed == baseSeed
        && project.project.minY == baseMinY
        && project.project.maxY == baseMaxY;
  }

  boolean closed() {
    return closed;
  }

  boolean modified() {
    return !closed
        && (!text.get("seed").equals(Long.toString(baseSeed))
            || !text.get("minY").equals(Integer.toString(baseMinY))
            || !text.get("maxY").equals(Integer.toString(baseMaxY)));
  }

  private static Diagnostic problem(String field, String message) {
    return new Diagnostic(
        "INVALID_PROJECT_SETTING",
        Diagnostic.Severity.ERROR,
        message,
        "/project/" + field,
        null,
        null,
        field);
  }

  List<Diagnostic> validate() {
    if (cached != null) return cached;
    var problems = new ArrayList<Diagnostic>();
    var values = new HashMap<String, Long>();
    for (var field : FIELDS) {
      String raw = text.get(field);
      try {
        if (raw.length() > MAX_CHARACTERS || !raw.matches("[+-]?[0-9]+"))
          throw new NumberFormatException();
        long value = field.equals("seed") ? Long.parseLong(raw) : Integer.parseInt(raw);
        values.put(field, value);
      } catch (NumberFormatException invalid) {
        problems.add(
            problem(
                field,
                field.equals("seed")
                    ? "Project seed must be a signed 64-bit decimal integer."
                    : "Project Y bound must be a signed 32-bit decimal integer."));
      }
    }
    if (values.containsKey("minY")
        && values.containsKey("maxY")
        && values.get("minY") > values.get("maxY"))
      problems.add(
          new Diagnostic(
              "INVALID_BOUNDS",
              Diagnostic.Severity.ERROR,
              "Minimum Y must not exceed maximum Y.",
              "/project/maxY",
              null,
              null,
              "maxY"));
    cached = List.copyOf(problems);
    return cached;
  }

  List<Diagnostic> applyIssues() {
    return applyIssues;
  }

  boolean apply(EditorState state) {
    if (!current(state))
      throw new IllegalStateException(
          closed
              ? "Project Settings is closed"
              : "The project changed. Discard and reload Project Settings before applying.");
    var problems = validate();
    if (!problems.isEmpty()) {
      applyIssues = problems;
      state.issues = problems;
      state.validationScope = "Rejected Project Settings";
      for (var problem : problems)
        state.reportDiagnostic(problem, EditorEvents.Subsystem.PROJECT, true);
      return false;
    }
    long seed = Long.parseLong(text.get("seed"));
    int minY = Integer.parseInt(text.get("minY")), maxY = Integer.parseInt(text.get("maxY"));
    if (!state.change(
        p -> {
          p.project.seed = seed;
          p.project.minY = minY;
          p.project.maxY = maxY;
        })) {
      applyIssues = List.copyOf(state.issues);
      return false;
    }
    closed = true;
    return true;
  }

  void cancel() {
    closed = true;
  }

  static String field(EditorEvents.Target target) {
    if (target == null
        || !target.settings()
        || target.resource() != null
        || target.control() != null) return null;
    String parameter = Objects.requireNonNullElse(target.parameter(), "");
    if (parameter.startsWith("project/")) parameter = parameter.substring(8);
    if (FIELDS.contains(parameter)) return parameter;
    if (!Set.of("", "project").contains(parameter)) return null;
    String pointer = Objects.requireNonNullElse(target.pointer(), "");
    for (var field : FIELDS) if (pointer.equals("/project/" + field)) return field;
    return pointer.equals("/project") ? "maxY" : null;
  }
}
