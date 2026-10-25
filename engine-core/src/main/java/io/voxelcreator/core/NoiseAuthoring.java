package io.voxelcreator.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * Owned, compiler-checked structural edits for a private noise draft. No document is committed
 * here.
 */
public final class NoiseAuthoring {
  private NoiseAuthoring() {}

  public static final List<String> TYPES =
      List.of("Scalar", "Boolean", "Vector2", "Vector3", "Coordinates2D", "Coordinates3D");

  public enum Action {
    UP,
    DOWN,
    DUPLICATE,
    DELETE,
    DISABLE,
    ENABLE,
    DELETE_PARAMETER,
    DUPLICATE_PARAMETER
  }

  public enum Resolution {
    REJECT,
    SOURCE,
    REPLACE,
    INLINE_PARAMETER
  }

  public record Request(Action action, UUID target, Resolution resolution, UUID replacement) {
    public Request {
      Objects.requireNonNull(action);
      Objects.requireNonNull(target);
      Objects.requireNonNull(resolution);
    }

    public static Request simple(Action action, UUID target) {
      return new Request(action, target, Resolution.REJECT, null);
    }
  }

  public static final class Plan {
    private final JsonNode base;
    private final Noise candidate;
    private final List<Diagnostic> diagnostics;
    private final UUID selected;

    private Plan(Noise source, Noise candidate, List<Diagnostic> diagnostics, UUID selected) {
      base = ProjectIO.JSON.valueToTree(source);
      this.candidate = copy(candidate);
      this.diagnostics = List.copyOf(diagnostics);
      this.selected = selected;
    }

    public boolean valid() {
      return diagnostics.isEmpty();
    }

    public List<Diagnostic> diagnostics() {
      return diagnostics;
    }

    public UUID selected() {
      return selected;
    }

    public Noise candidate() {
      return copy(candidate);
    }

    public void applyTo(Noise draft) {
      if (!base.equals(ProjectIO.JSON.valueToTree(draft)))
        throw new IllegalStateException("Noise draft changed; preview this edit again");
      if (!valid())
        throw new IllegalStateException("Resolve the structural edit before applying it");
      var owned = candidate();
      draft.coordinateTransforms = owned.coordinateTransforms;
      draft.processing = owned.processing;
      draft.sharedParameters = owned.sharedParameters;
      draft.pinnedControls = owned.pinnedControls;
      draft.outputBinding = owned.outputBinding;
    }
  }

  public static Noise copy(Noise source) {
    return ProjectIO.JSON.convertValue(source, Noise.class);
  }

  public static Plan plan(ProjectData project, Noise draft, Request request) {
    Objects.requireNonNull(request);
    var candidate = copy(draft);
    var issues = new ArrayList<Diagnostic>();
    UUID selected = request.target();
    try {
      if (request.action() == Action.DUPLICATE_PARAMETER) {
        var original =
            candidate.sharedParameters.stream()
                .filter(p -> p.uuid.equals(request.target()))
                .findFirst()
                .orElseThrow();
        var duplicate = ResourceTransactions.duplicate(original, Parameter.class);
        duplicate.id =
            ResourceTransactions.unique(
                original.id, candidate.sharedParameters.stream().map(p -> p.id).toList());
        candidate.sharedParameters.add(candidate.sharedParameters.indexOf(original) + 1, duplicate);
        selected = duplicate.uuid;
      } else if (request.action() == Action.DELETE_PARAMETER) {
        var parameter =
            candidate.sharedParameters.stream()
                .filter(p -> p.uuid.equals(request.target()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("Shared parameter no longer exists"));
        var references =
            bindings(candidate).stream()
                .filter(
                    b ->
                        b.sourceKind.equals("Shared Parameter")
                            && parameter.uuid.equals(b.parameterUuid))
                .toList();
        if (!references.isEmpty() && request.resolution() == Resolution.REJECT)
          throw new IllegalArgumentException(
              "Shared parameter is used; choose replacement or Inline Current Value");
        Parameter replacement = null;
        if (request.resolution() == Resolution.REPLACE) {
          replacement =
              candidate.sharedParameters.stream()
                  .filter(p -> p.uuid.equals(request.replacement()))
                  .findFirst()
                  .orElseThrow();
          if (replacement.uuid.equals(parameter.uuid) || !replacement.type.equals(parameter.type))
            throw new IllegalArgumentException(
                "Choose another shared parameter with the same type");
        }
        for (var binding : references) {
          if (request.resolution() == Resolution.INLINE_PARAMETER) {
            source(binding, "Constant");
            binding.value = parameter.value.deepCopy();
          } else if (replacement != null) binding.parameterUuid = replacement.uuid;
          else throw new IllegalArgumentException("Choose replacement or Inline Current Value");
        }
        for (var pin : candidate.pinnedControls)
          if (pin.targetUuid.equals(parameter.uuid) && replacement != null)
            pin.targetUuid = replacement.uuid;
        if (replacement == null)
          candidate.pinnedControls.removeIf(pin -> pin.targetUuid.equals(parameter.uuid));
        candidate.sharedParameters.remove(parameter);
        selected = replacement == null ? null : replacement.uuid;
      } else {
        List<Item> list =
            candidate.coordinateTransforms.stream().anyMatch(i -> i.uuid.equals(request.target()))
                ? candidate.coordinateTransforms
                : candidate.processing;
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid.equals(request.target())) at = i;
        if (at < 0) throw new IllegalArgumentException("Processing item no longer exists");
        var item = list.get(at);
        switch (request.action()) {
          case UP, DOWN -> {
            int to = at + (request.action() == Action.UP ? -1 : 1);
            if (to >= 0 && to < list.size()) Collections.swap(list, at, to);
          }
          case DUPLICATE -> {
            var duplicate = ResourceTransactions.duplicate(item, Item.class);
            var names = new ArrayList<String>();
            candidate.coordinateTransforms.forEach(i -> names.add(i.id));
            candidate.processing.forEach(i -> names.add(i.id));
            duplicate.id = ResourceTransactions.unique(item.id, names);
            list.add(at + 1, duplicate);
            selected = duplicate.uuid;
          }
          case ENABLE -> item.enabled = true;
          case DELETE, DISABLE -> {
            if (request.action() == Action.DELETE) list.remove(item);
            var references =
                bindings(candidate).stream()
                    .filter(
                        b ->
                            Set.of("Earlier Result", "Earlier Field Prefix").contains(b.sourceKind)
                                && item.uuid.equals(b.itemUuid))
                    .toList();
            if (!references.isEmpty() && request.resolution() == Resolution.REJECT)
              throw new IllegalArgumentException(
                  "Item is used by inputs or Output; choose an explicit replacement");
            Item replacement = null;
            if (request.resolution() == Resolution.REPLACE) {
              replacement =
                  list.stream()
                      .filter(i -> i.uuid.equals(request.replacement()))
                      .findFirst()
                      .orElseThrow();
              if (replacement.uuid.equals(item.uuid)
                  || !replacement.enabled
                  || !replacement.resultType.equals(item.resultType))
                throw new IllegalArgumentException(
                    "Choose another enabled result with the same type");
            }
            for (var binding : references) {
              if (request.resolution() == Resolution.SOURCE
                  && list == candidate.processing
                  && binding.type.equals("Scalar")) {
                source(
                    binding,
                    binding.sourceKind.equals("Earlier Field Prefix")
                        ? "Source Field"
                        : "Source Result");
              } else if (replacement != null) binding.itemUuid = replacement.uuid;
              else
                throw new IllegalArgumentException(
                    "Replacement must preserve type and coordinate/processing stage");
            }
            if (request.action() == Action.DISABLE) item.enabled = false;
            else {
              list.remove(item);
              Item chosen = replacement;
              for (var pin : candidate.pinnedControls)
                if (pin.targetUuid.equals(item.uuid) && chosen != null)
                  pin.targetUuid = chosen.uuid;
              if (chosen == null)
                candidate.pinnedControls.removeIf(pin -> pin.targetUuid.equals(item.uuid));
              selected = chosen == null ? null : chosen.uuid;
            }
          }
          default -> throw new IllegalArgumentException("Unsupported item edit");
        }
      }
      var snapshot = ProjectIO.copy(project);
      List<Noise> resources = candidate.dimension == 2 ? snapshot.noise2D : snapshot.noise3D;
      int at = -1;
      for (int i = 0; i < resources.size(); i++)
        if (resources.get(i).uuid.equals(candidate.uuid)) at = i;
      if (at < 0)
        throw new IllegalArgumentException(
            "Noise resource no longer exists in the current project");
      resources.set(at, copy(candidate));
      issues.addAll(NoiseCompiler.validate(snapshot));
    } catch (RuntimeException invalid) {
      issues.add(
          Diagnostic.error(
              "INVALID_NOISE_EDIT",
              "/noise/" + draft.uuid,
              Objects.requireNonNullElse(invalid.getMessage(), "Choose a valid edit resolution")));
    }
    return new Plan(draft, candidate, issues, selected);
  }

  public static List<Binding> bindings(Noise noise) {
    var bindings = new ArrayList<Binding>();
    for (var items : List.of(noise.coordinateTransforms, noise.processing))
      for (var item : items) bindings.addAll(item.inputs.values());
    bindings.add(noise.outputBinding);
    return bindings;
  }

  public static JsonNode defaultValue(String type) {
    if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported type " + type);
    return ProjectIO.JSON.valueToTree(
        type.equals("Scalar")
            ? 0.0
            : type.equals("Boolean")
                ? false
                : type.endsWith("2") || type.endsWith("2D")
                    ? new double[] {0, 0}
                    : new double[] {0, 0, 0});
  }

  /** Source changes clear inactive references; explicit type edits reset to a typed constant. */
  public static void type(Binding binding, String type) {
    var value = defaultValue(type);
    binding.type = type;
    binding.value = value;
    source(binding, "Constant");
  }

  public static void source(Binding binding, String kind) {
    if (!Set.of(
            "Constant",
            "Shared Parameter",
            "Ambient Value",
            "Source Result",
            "Earlier Result",
            "Noise Generator Reference",
            "Source Field",
            "Earlier Field Prefix",
            "Noise Generator Field")
        .contains(kind)) throw new IllegalArgumentException("Unsupported binding source " + kind);
    String previous = binding.sourceKind;
    binding.sourceKind = kind;
    binding.itemUuid = null;
    binding.parameterUuid = null;
    binding.noiseGeneratorUuid = null;
    binding.ambientKey = null;
    if (kind.equals("Constant") && (!kind.equals(previous) || binding.value == null))
      binding.value = defaultValue(binding.type);
    if (!kind.equals("Constant")) binding.value = null;
  }

  public static Map<String, Object> registry() {
    var result = new LinkedHashMap<String, Object>();
    result.put("registryVersion", 1);
    result.put("projectFormatVersion", 2);
    result.put("types", TYPES);
    result.put("stages", List.of("coordinateTransforms", "processing"));
    result.put("finalOutputType", "Scalar");
    result.put("structuralEditsUseCompilerValidation", true);
    result.put("draftPublicationRequiresUnchangedBase", true);
    result.put(
        "pinValueAuthority", "original shared parameter; item pins navigate to original controls");
    return Collections.unmodifiableMap(result);
  }
}
