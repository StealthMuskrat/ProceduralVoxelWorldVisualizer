package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Immutable source-scoped trace presentation. Navigation never replays a historical array index.
 */
public final class TraceSnapshot {
  public enum Freshness {
    CURRENT,
    PROJECT_CHANGED,
    OTHER_PROJECT
  }

  public record Row(String label, String value, EditorEvents.Target target) {}

  private final UUID project;
  private final long revision;
  private final GenerationTrace trace;
  private final Map<Integer, String> names;
  private final Map<Integer, UUID> voxelTypes;
  private final Map<UUID, String> fingerprints;
  private final List<List<Row>> rows;
  private final String summary;
  private final long bytes;

  public TraceSnapshot(ProjectData source, long revision, GenerationTrace trace) {
    this.project = source.project.uuid;
    this.revision = revision;
    this.trace = Objects.requireNonNull(trace);
    var names = new LinkedHashMap<Integer, String>();
    names.put(0, "EMPTY");
    var types = new LinkedHashMap<Integer, UUID>();
    for (int i = 0; i < source.voxelTypes.size(); i++) {
      names.put(i + 1, source.voxelTypes.get(i).id);
      types.put(i + 1, source.voxelTypes.get(i).uuid);
    }
    voxelTypes = Map.copyOf(types);
    this.names = Map.copyOf(names);
    var all = new ArrayList<List<Row>>();
    for (var step : trace.steps()) all.add(build(step));
    rows = List.copyOf(all);
    var hashes = new LinkedHashMap<UUID, String>();
    for (var group : rows)
      for (var row : group)
        if (row.target != null) {
          UUID id = row.target.resource();
          if (!hashes.containsKey(id)) {
            var entry = ResourceGraph.find(source, id);
            if (entry != null) hashes.put(id, hash(entry.resource()));
          }
        }
    fingerprints = Map.copyOf(hashes);
    summary =
        "Coordinate "
            + trace.position()
            + " | Final "
            + type(trace.finalType())
            + " | "
            + trace.steps().size()
            + " operations | snapshot revision "
            + revision
            + trace.steps().stream()
                .filter(step -> !step.action().equals("NO_CHANGE"))
                .reduce((a, b) -> b)
                .map(
                    step ->
                        " | Last deciding stage: "
                            + step.layerId()
                            + " / "
                            + step.operationId()
                            + " ("
                            + step.action()
                            + ")")
                .orElse("");
    long estimate =
        summary.length() * 2L + 256 + fingerprints.size() * 160L + trace.steps().size() * 256L;
    for (var name : names.values()) estimate += 96L + name.length() * 2L;
    for (var group : rows)
      for (var row : group) estimate += 192L + 2L * (row.label.length() + row.value.length());
    bytes = estimate;
  }

  public GenerationTrace trace() {
    return trace;
  }

  public long revision() {
    return revision;
  }

  public UUID project() {
    return project;
  }

  public Freshness freshness(UUID currentProject, long currentRevision) {
    if (!project.equals(currentProject)) return Freshness.OTHER_PROJECT;
    return revision == currentRevision ? Freshness.CURRENT : Freshness.PROJECT_CHANGED;
  }

  public String summary() {
    return summary;
  }

  public String type(int id) {
    return names.getOrDefault(id, "Unknown snapshot type " + id);
  }

  public EditorEvents.Target finalTypeTarget() {
    return target(voxelTypes.get(trace.finalType()), null, null);
  }

  public List<Row> rows(int step) {
    return rows.get(step);
  }

  public long retainedBytes() {
    return bytes;
  }

  private EditorEvents.Target target(UUID resource, UUID control, String parameter) {
    return resource == null
        ? null
        : new EditorEvents.Target(project, resource, control, parameter, "", false);
  }

  private EditorEvents.Target setting(GenerationTrace.Details details, String property) {
    var source = details.settingTargets().get(property);
    return source == null
        ? null
        : target(source.resourceUuid(), null, "settings/" + source.property());
  }

  private List<Row> build(GenerationTrace.Step step) {
    var rows = new ArrayList<Row>();
    rows.add(new Row("Outcome", step.reason(), target(step.operationUuid(), null, null)));
    rows.add(new Row("Layer", step.layerId(), target(step.layerUuid(), null, null)));
    rows.add(
        new Row(
            "Operation",
            step.operationId() + " / " + step.action(),
            target(step.operationUuid(), null, null)));
    rows.add(
        new Row(
            "Primary field",
            Double.isNaN(step.field()) ? "not sampled" : Double.toString(step.field()),
            target(step.noiseUuid(), null, "outputBinding")));
    rows.add(
        new Row(
            "Voxel state",
            type(step.previous()) + " → " + type(step.result()),
            target(voxelTypes.get(step.result()), null, null)));
    var d = step.details();
    if (d == null) return List.copyOf(rows);
    for (var c : d.comparisons())
      rows.add(
          new Row(
              "Compare " + c.property(),
              c.left()
                  + " "
                  + c.operator()
                  + " "
                  + c.right()
                  + " : "
                  + (c.passed() ? "passed" : "rejected"),
              setting(d, c.property())));
    rows.add(
        new Row(
            "Selection",
            "selected="
                + d.selected()
                + " | gate="
                + outcome(d.gatePassed())
                + " | previous-state predicate="
                + outcome(d.predicatePassed()),
            setting(d, "voxelStatePredicate")));
    rows.add(
        new Row(
            "Probability",
            d.probability() + " | deterministic draw=" + d.random(),
            setting(d, "probability")));
    if (d.sampleCoordinates() != null)
      rows.add(
          new Row(
              "Sample coordinates",
              d.sampleCoordinates().toString(),
              setting(d, "samplingTransforms")));
    if (d.matchedInterval() != null)
      rows.add(
          new Row(
              "Type interval",
              "row " + (d.matchedInterval() + 1) + " → " + type(d.candidateType()),
              setting(d, "typeIntervals")));
    for (String key : SettingsResolver.PROPERTIES)
      if (d.effectiveSettings().containsKey(key))
        rows.add(
            new Row(
                "Setting " + key,
                d.effectiveSettings().get(key)
                    + " | "
                    + step.settingsSources().getOrDefault(key, "historical source unavailable"),
                setting(d, key)));
    for (var g : d.gates()) {
      rows.add(
          new Row(
              "Gate " + g.path(),
              g.kind()
                  + " | value="
                  + g.value()
                  + " | passed="
                  + g.passed()
                  + " | draw="
                  + g.random(),
              setting(d, "fieldGates")));
      if (g.noiseUuid() != null)
        rows.add(
            new Row(
                "Gate field",
                g.noiseUuid().toString(),
                target(g.noiseUuid(), null, "outputBinding")));
    }
    for (var driver : d.drivers()) {
      rows.add(
          new Row(
              "Driver " + driver.property(),
              "sample="
                  + driver.sample()
                  + " mapped="
                  + driver.mapped()
                  + " constant="
                  + driver.constant()
                  + " effective="
                  + driver.effective(),
              setting(d, driver.property() + "Driver")));
      if (driver.noiseUuid() != null)
        rows.add(
            new Row(
                "Driver field",
                driver.noiseUuid().toString(),
                target(driver.noiseUuid(), null, "outputBinding")));
    }
    for (var parameter : d.parameters()) {
      rows.add(
          new Row(
              "Parameter row " + (parameter.row() + 1),
              parameter.phase()
                  + " | "
                  + parameter.destination()
                  + (parameter.transformIndex() == null
                      ? ""
                      : " [transform row " + (parameter.transformIndex() + 1) + "]")
                  + " "
                  + parameter.combination()
                  + " | sample="
                  + parameter.sample()
                  + " mapped="
                  + parameter.mapped()
                  + " previous="
                  + parameter.previous()
                  + " effective="
                  + parameter.effective()
                  + " | coordinates="
                  + parameter.sampleCoordinates(),
              setting(d, "parameterDrivers")));
      if (parameter.noiseUuid() != null)
        rows.add(
            new Row(
                "Parameter field",
                parameter.noiseUuid().toString(),
                target(parameter.noiseUuid(), null, "outputBinding")));
    }
    if (d.primaryField() != null) {
      for (var e : d.primaryField().events()) {
        String parameter =
            e.phase().equals("SOURCE")
                ? "algorithm"
                : e.phase().equals("OUTPUT")
                    ? "outputBinding"
                    : e.kind().equals("EXPRESSION") ? "expression" : null;
        rows.add(
            new Row(
                "Noise " + e.phase() + " / " + e.id(),
                e.kind()
                    + " "
                    + e.type()
                    + " "
                    + e.values()
                    + " @ "
                    + e.x()
                    + ","
                    + e.y()
                    + ","
                    + e.z(),
                target(
                    e.resourceUuid(),
                    e.phase().equals("OUTPUT") ? null : e.itemUuid(),
                    parameter)));
      }
      if (d.primaryField().omitted() > 0)
        rows.add(
            new Row(
                "Observation limit",
                d.primaryField().omitted()
                    + " additional observations omitted; computed result is complete",
                null));
    }
    return List.copyOf(rows);
  }

  private static String outcome(Boolean v) {
    return v == null ? "not reached" : v ? "passed" : "rejected";
  }

  public boolean available(EditorState state, EditorEvents.Target target) {
    if (target == null
        || !project.equals(state.project().project.uuid)
        || !state.canNavigate(target)) return false;
    var entry = ResourceGraph.find(state.project(), target.resource());
    return entry != null
        && Objects.equals(fingerprints.get(target.resource()), hash(entry.resource()));
  }

  private static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      ProjectIO.JSON
                          .valueToTree(value)
                          .toString()
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public String details() {
    var text = new StringBuilder(summary);
    for (int i = 0; i < rows.size(); i++) {
      var step = trace.steps().get(i);
      text.append("\n\n").append(step.layerId()).append(" / ").append(step.operationId());
      for (var row : rows.get(i))
        text.append('\n').append(row.label).append(": ").append(row.value);
    }
    return text.toString();
  }
}
