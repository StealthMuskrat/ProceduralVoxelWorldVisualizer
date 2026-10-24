package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.*;
import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectData.Predicate;
import java.util.*;
import java.util.function.*;

public final class PropertyPanel {
  private final EditorState state;
  private final UiFields f = new UiFields();
  private UUID last;
  private CurveDialog curve;
  private boolean smokeCurveDrawn;

  public void smokeCurve(boolean visible) {
    if (visible) {
      var c = new Curve();
      c.points.add(1, new Point(.5, .8));
      c.points.forEach(p -> p.segmentInterpolation = "BEZIER");
      curve = new CurveDialog(c, ignored -> {});
      curve.selectAll();
    } else if (curve != null) curve.cancel();
  }

  public void assertSmokeCurveRendered() {
    if (!smokeCurveDrawn) throw new IllegalStateException("Curve editor did not render");
    System.out.println("CURVE_EDITOR_NATIVE_PASS");
  }

  private Settings smokeWorldSettings;
  private String smokeWorldSection;
  private final Set<String> smokeWorldDrawn = new HashSet<>();

  public void smokeWorldControls(String section) {
    smokeWorldSection = section;
    smokeWorldSettings =
        section == null
            ? null
            : ProjectIO.JSON.convertValue(
                ProjectIO.JSON.valueToTree(
                    state.project().worldGenerator.layers.getFirst().defaults),
                Settings.class);
  }

  public void assertSmokeWorldControlsRendered() {
    if (!smokeWorldDrawn.containsAll(Set.of("Transforms", "Types", "Drivers and gates")))
      throw new IllegalStateException("World-use controls were not rendered: " + smokeWorldDrawn);
    System.out.println("WORLD_USE_CONTROLS_NATIVE_PASS");
  }

  private void drawSmokeWorldControls() {
    if (smokeWorldSettings == null) return;
    ImGui.setNextWindowPos(290, 140, ImGuiCond.Appearing);
    ImGui.setNextWindowSize(740, 650, ImGuiCond.Appearing);
    if (ImGui.begin("World-use Controls (smoke)")) {
      smokeWorldDrawn.add(smokeWorldSection);
      ImGui.text("Placement " + smokeWorldSection);
      ImGui.separator();
      switch (smokeWorldSection) {
        case "Transforms" -> samplingTransforms(smokeWorldSettings.samplingTransforms);
        case "Types" -> typeIntervals(smokeWorldSettings.typeIntervals);
        default -> {
          ImGui.pushID("probability");
          fieldDriver(smokeWorldSettings.probabilityDriver);
          ImGui.popID();
          ImGui.separator();
          gate(smokeWorldSettings.fieldGates, 0);
        }
      }
    }
    ImGui.end();
  }

  private UUID focusedControl;

  public void goToControl(UUID resource, UUID control) {
    Object object = EditorState.find(state.project(), resource);
    if (object instanceof Noise n) {
      state.select("noise" + n.dimension + "D", resource);
      focusedControl = control;
    }
  }

  private void focusRow(UUID uuid) {
    if (uuid.equals(focusedControl)) {
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    }
  }

  private void revealRow(UUID uuid) {
    if (uuid.equals(focusedControl)) {
      ImGui.setScrollHereY(.2f);
      focusedControl = null;
    }
  }

  public PropertyPanel(EditorState s) {
    state = s;
  }

  public void draw() {
    ImGui.begin("Properties");
    ImGui.setWindowFontScale(state.user.appearance.windowScales.getOrDefault("Properties", 1f));
    if (!Objects.equals(last, state.selected)) {
      last = state.selected;
      f.clear();
    }
    if (state.multiSelection.size() > 1) {
      ImGui.text(state.multiSelection.size() + " sibling resources selected");
      ImGui.textWrapped("Select one resource to edit its properties.");
    } else if (state.draft == null) {
      ImGui.textDisabled("No resource selected");
      ImGui.textWrapped(
          "Create a noise generator from Resources, or add a layer to the World Generator.");
    } else {
      ImGui.textDisabled(state.selectedKind.toUpperCase(Locale.ROOT));
      if (ImGui.button("Apply")) state.applyDraft();
      ImGui.sameLine();
      if (ImGui.button("Revert")) {
        state.refreshDraft();
        f.clear();
      }
      f.tooltip("Apply validates this edit and commits one undoable transaction.");
      if (!state.issues.isEmpty()) {
        ImGui.textColored(1, .4f, .35f, 1, "! " + state.issues.size() + " validation issue(s)");
        for (var d : state.issues) ImGui.textWrapped(d.toString());
      }
      ImGui.separator();
      if (state.draft instanceof Identity r) f.text("ID", r.id, v -> r.id = v);
      if (state.draft instanceof Noise n) noise(n);
      else if (state.draft instanceof Layer l) {
        f.bool("Enabled", l.enabled, v -> l.enabled = v);
        ImGui.textWrapped("Placements inherit every property below until explicitly overridden.");
        settings(l.defaults);
      } else if (state.draft instanceof Operation op) placement(op);
      else if (state.draft instanceof VoxelType t) {
        float[] rgb = {t.color.r / 255f, t.color.g / 255f, t.color.b / 255f};
        if (ImGui.colorEdit3("Color", rgb)) {
          t.color.r = Math.round(rgb[0] * 255);
          t.color.g = Math.round(rgb[1] * 255);
          t.color.b = Math.round(rgb[2] * 255);
        }
        ImGui.textWrapped(
            "Palette edits affect appearance; semantic voxel identity remains the same.");
      } else if (state.draft instanceof Membership m) {
        for (var t : state.project().voxelTypes)
          f.bool(
              t.id,
              m.voxelTypeUuids.contains(t.uuid),
              v -> {
                if (v) m.voxelTypeUuids.add(t.uuid);
                else m.voxelTypeUuids.remove(t.uuid);
              });
      } else if (state.draft instanceof Folder folder) {
        ImGui.text("Organizational folder: " + folder.rootKind);
        ImGui.textWrapped(
            "Folder placement never changes generation or adds resource inheritance.");
      }
    }
    ImGui.end();
    if (curve != null) {
      smokeCurveDrawn = true;
      if (curve.draw()) curve = null;
    }
    drawSmokeWorldControls();
  }

  private void noise(Noise n) {
    ImGui.text("Dimension: " + n.dimension + "D  |  Output: Scalar");
    if (ImGui.collapsingHeader("Source", ImGuiTreeNodeFlags.DefaultOpen)) {
      f.choice("Algorithm", n.algorithm.key, NoiseAlgorithms.KEYS, v -> n.algorithm.key = v);
      longText("Seed offset", n.seedOffset, v -> n.seedOffset = v);
      if (!Set.of("CONSTANT", "GRADIENT_Y", "SPHERE_SDF", "BOX_SDF").contains(n.algorithm.key)) {
        algorithmNumber(n, "frequency", .01);
        if (n.algorithm.key.equals("CELLULAR")) {
          algorithmNumber(n, "cellularDistance", 1);
          algorithmNumber(n, "cellularReturn", 1);
          algorithmNumber(n, "jitter", 1);
        }
        algorithmNumber(n, "fractal", 0);
        algorithmNumber(n, "octaves", 3);
        algorithmNumber(n, "lacunarity", 2);
        algorithmNumber(n, "gain", .5);
      } else if (n.algorithm.key.equals("CONSTANT")) algorithmNumber(n, "value", 0);
      else if (n.algorithm.key.equals("SPHERE_SDF")) algorithmNumber(n, "radius", 16);
      else if (n.algorithm.key.equals("BOX_SDF")) {
        for (String axis : List.of("X", "Y", "Z"))
          if (!axis.equals("Y") || n.dimension == 3) algorithmNumber(n, "extent" + axis, 16);
      }
    }
    if (focusedControl != null
        && n.coordinateTransforms.stream().anyMatch(i -> i.uuid.equals(focusedControl)))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Coordinates & Transforms")) {
      for (int i = 0; i < n.coordinateTransforms.size(); i++) {
        var item = n.coordinateTransforms.get(i);
        ImGui.pushID(item.uuid.toString());
        focusRow(item.uuid);
        if (ImGui.treeNode("[" + i + "] " + item.id + "  " + item.kind)) {
          revealRow(item.uuid);
          f.bool("Enabled", item.enabled, v -> item.enabled = v);
          f.text("ID", item.id, v -> item.id = v);
          boolean explicit = item.inputs.containsKey("coordinates");
          f.bool(
              "Explicit input coordinates",
              explicit,
              v -> {
                if (v) {
                  var value = Binding.constant(0);
                  value.type = n.dimension == 2 ? "Coordinates2D" : "Coordinates3D";
                  value.value =
                      ProjectIO.JSON.valueToTree(
                          n.dimension == 2 ? new double[] {0, 0} : new double[] {0, 0, 0});
                  item.inputs.put("coordinates", value);
                } else item.inputs.remove("coordinates");
              });
          if (item.inputs.containsKey("coordinates")) {
            ImGui.pushID("coordinates");
            binding(n, item, i, item.inputs.get("coordinates"));
            ImGui.popID();
          }
          if (item.kind.equals("ROTATE")) {
            if (n.dimension == 2) transformControl(n, item, i, "angle", 0);
            else {
              for (String axis : List.of("X", "Y", "Z"))
                transformControl(
                    n,
                    item,
                    i,
                    "angle" + axis,
                    item.parameters.containsKey("angle") && axis.equals("Y")
                        ? item.parameters.get("angle").asDouble()
                        : 0);
              f.choice(
                  "Rotation order",
                  item.parameters.containsKey("rotationOrder")
                      ? item.parameters.get("rotationOrder").asText()
                      : "XYZ",
                  List.of("XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX"),
                  v -> item.parameters.put("rotationOrder", ProjectIO.JSON.valueToTree(v)));
            }
            ImGui.textWrapped("Angles are degrees; positive Y rotation turns +X toward +Z.");
          } else if (item.kind.equals("DOMAIN_WARP")) {
            transformControl(n, item, i, "amplitude", 8);
            parameter(item, "frequency", .01);
            parameter(item, "seedOffset", 0);
            for (String axis : n.dimension == 2 ? List.of("X", "Z") : List.of("X", "Y", "Z")) {
              String name = "warp" + axis;
              f.bool(
                  "External " + name,
                  item.inputs.containsKey(name),
                  v -> {
                    if (v) item.inputs.put(name, Binding.constant(0));
                    else item.inputs.remove(name);
                  });
              if (item.inputs.containsKey(name)) {
                ImGui.pushID(name);
                binding(n, item, i, item.inputs.get(name));
                ImGui.popID();
              }
            }
          } else
            for (String axis : n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"))
              transformControl(n, item, i, axis, item.kind.equals("SCALE") ? 1 : 0);
          if (Set.of("ROTATE", "SCALE").contains(item.kind))
            for (String axis : n.dimension == 2 ? List.of("X", "Z") : List.of("X", "Y", "Z"))
              transformControl(n, item, i, "pivot" + axis, 0);
          itemActions(n.coordinateTransforms, i);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      if (ImGui.button("Add Transform")) ImGui.openPopup("transform-add");
      if (ImGui.beginPopup("transform-add")) {
        for (String k : List.of("TRANSLATE", "SCALE", "ROTATE", "DOMAIN_WARP"))
          if (ImGui.menuItem(k)) {
            var i = new Item();
            i.kind = k;
            i.resultType = n.dimension == 2 ? "Coordinates2D" : "Coordinates3D";
            i.id = uniqueItem(n, k.toLowerCase(Locale.ROOT));
            n.coordinateTransforms.add(i);
          }
        ImGui.endPopup();
      }
    }
    if (focusedControl != null
        && n.processing.stream()
            .anyMatch(i -> i.uuid.equals(focusedControl) || curveContains(i, focusedControl)))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Ordered Processing", ImGuiTreeNodeFlags.DefaultOpen)) {
      ImGui.textDisabled("Global evaluation order is shown in [brackets]");
      for (int i = 0; i < n.processing.size(); i++) {
        var item = n.processing.get(i);
        ImGui.pushID(item.uuid.toString());
        if (focusedControl != null && curveContains(item, focusedControl))
          focusedControl = item.uuid;
        focusRow(item.uuid);
        if (ImGui.treeNode(
            "[" + i + "] " + item.id + "  " + item.kind + (item.enabled ? "" : " (disabled)"))) {
          revealRow(item.uuid);
          f.text("ID", item.id, v -> item.id = v);
          f.bool("Enabled", item.enabled, v -> item.enabled = v);
          ImGui.textDisabled(
              ProcessingRegistry.ITEMS.containsKey(item.kind)
                  ? ProcessingRegistry.ITEMS.get(item.kind).category()
                  : "Unknown operation");
          if (item.kind.equals("CONSTANT"))
            f.choice(
                "Result type",
                item.resultType,
                List.of("Scalar", "Boolean", "Vector2", "Vector3"),
                v -> {
                  item.resultType = v;
                  item.parameters.put(
                      "value",
                      ProjectIO.JSON.valueToTree(
                          v.equals("Scalar")
                              ? 0
                              : v.equals("Boolean")
                                  ? false
                                  : v.equals("Vector2")
                                      ? new double[] {0, 0}
                                      : new double[] {0, 0, 0}));
                });
          if (item.kind.equals("SELECT"))
            f.choice(
                "Result type",
                item.resultType,
                List.of(
                    "Scalar", "Boolean", "Vector2", "Vector3", "Coordinates2D", "Coordinates3D"),
                v -> item.resultType = v);
          if (Set.of("COORDINATES_TO_VECTOR", "VECTOR_ROTATE").contains(item.kind))
            f.choice(
                "Result type",
                item.resultType,
                List.of("Vector2", "Vector3"),
                v -> item.resultType = v);
          if (item.kind.equals("VECTOR_TO_COORDINATES"))
            f.choice(
                "Result type",
                item.resultType,
                List.of("Coordinates2D", "Coordinates3D"),
                v -> item.resultType = v);
          if (item.kind.equals("EXPRESSION")) {
            f.choice(
                "Result type",
                item.resultType,
                List.of("Scalar", "Boolean"),
                v -> item.resultType = v);
            String expr =
                item.parameters.containsKey("expression")
                    ? item.parameters.get("expression").asText()
                    : "";
            f.text(
                "Expression",
                expr,
                v -> item.parameters.put("expression", ProjectIO.JSON.valueToTree(v)));
          }
          if (java.util.Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE").contains(item.kind))
            f.choice(
                "Result type",
                item.resultType,
                List.of("Scalar", "Vector2", "Vector3"),
                v -> item.resultType = v);
          if (item.kind.equals("NORMALIZE"))
            f.choice(
                "Result type",
                item.resultType,
                List.of("Vector2", "Vector3"),
                v -> item.resultType = v);
          for (var e : new ArrayList<>(item.inputs.entrySet())) {
            ImGui.pushID(e.getKey());
            if (ImGui.treeNode("Input: " + e.getKey() + " [" + e.getValue().type + "]")) {
              if (e.getKey().equals("field") && ProcessingRegistry.SAMPLED.contains(item.kind))
                fieldBinding(n, i, e.getValue());
              else binding(n, item, i, e.getValue());
              ImGui.treePop();
            }
            ImGui.popID();
          }
          if (item.kind.equals("EXPRESSION") && ImGui.button("Add Named Input")) {
            String name = "input";
            int count = 1;
            while (item.inputs.containsKey(name)) name = "input" + (count++);
            item.inputs.put(name, Binding.constant(0));
          }
          switch (item.kind) {
            case "REMAP" -> {
              parameter(item, "inputMin", -1);
              parameter(item, "inputMax", 1);
              parameter(item, "outputMin", 0);
              parameter(item, "outputMax", 1);
              f.bool(
                  "Clamp output",
                  item.parameters.containsKey("clamp") && item.parameters.get("clamp").asBoolean(),
                  v -> item.parameters.put("clamp", ProjectIO.JSON.valueToTree(v)));
            }
            case "CLAMP" -> {
              parameter(item, "min", 0);
              parameter(item, "max", 1);
            }
            case "TERRACE" -> {
              parameter(item, "levels", 8);
              parameter(item, "transition", 0);
            }
            case "FALLOFF" -> {
              parameter(item, "extent", 64);
              parameter(item, "centerX", 0);
              if (n.dimension == 3) parameter(item, "centerY", 0);
              parameter(item, "centerZ", 0);
            }
            case "CONSTANT" ->
                constantEditor(
                    item.resultType,
                    item.parameters.get("value"),
                    v -> item.parameters.put("value", v));
            case "SPHERE" -> {
              parameter(item, "radius", 16);
              parameter(item, "centerX", 0);
              if (n.dimension == 3) parameter(item, "centerY", 0);
              parameter(item, "centerZ", 0);
            }
            case "BOX" -> {
              parameter(item, "extentX", 16);
              parameter(item, "extentY", 16);
              parameter(item, "extentZ", 16);
            }
            case "SMOOTH_UNION" -> parameter(item, "smoothness", 1);
            case "COMPONENT" -> parameter(item, "component", 0);
            case "PROJECT_VECTOR", "PROJECT_COORDINATES", "EXPAND_VECTOR", "EXPAND_COORDINATES" ->
                f.choice(
                    "Retained axes",
                    item.parameters.containsKey("axes")
                        ? item.parameters.get("axes").asText()
                        : "XZ",
                    List.of("XZ", "XY", "YZ"),
                    v -> item.parameters.put("axes", ProjectIO.JSON.valueToTree(v)));
            case "VECTOR_ROTATE" -> {
              parameter(item, "angle", 0);
              f.choice(
                  "Rotation axis",
                  item.parameters.containsKey("axis") ? item.parameters.get("axis").asText() : "x",
                  List.of("x", "y", "z"),
                  v -> item.parameters.put("axis", ProjectIO.JSON.valueToTree(v)));
            }
            case "COORDINATE" -> {
              String axis =
                  item.parameters.containsKey("axis") ? item.parameters.get("axis").asText() : "x";
              f.choice(
                  "Axis",
                  axis,
                  n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"),
                  v -> item.parameters.put("axis", ProjectIO.JSON.valueToTree(v)));
            }
            case "CURVE" -> {
              if (ImGui.button("Edit Curve"))
                try {
                  Curve c =
                      item.parameters.containsKey("curve")
                          ? ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class)
                          : new Curve();
                  curve =
                      new CurveDialog(
                          c,
                          applied ->
                              item.parameters.put("curve", ProjectIO.JSON.valueToTree(applied)));
                } catch (RuntimeException invalid) {
                  state.notify(
                      "Error",
                      "Curve cannot open: "
                          + invalid.getMessage()
                          + ". Correct the definition or reset the curve.");
                }
              ImGui.sameLine();
              if (ImGui.button("Reset Curve"))
                item.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
            }
            case "NOISE_REFERENCE",
                "FBM",
                "RIDGED",
                "BILLOW",
                "PING_PONG",
                "OFFSET_SAMPLE",
                "DERIVATIVE",
                "SMOOTH_FILTER",
                "WARP_SAMPLE" -> {
              if (!item.inputs.containsKey("field")) {
                UUID legacy =
                    item.parameters.containsKey("noiseGeneratorUuid")
                        ? UUID.fromString(item.parameters.remove("noiseGeneratorUuid").asText())
                        : null;
                item.inputs.put(
                    "field", legacy == null ? Binding.sourceField() : Binding.noiseField(legacy));
              }
              f.bool(
                  "Explicit sample coordinates",
                  item.inputs.containsKey("coordinates"),
                  v -> {
                    if (v) {
                      Binding b = Binding.constant(0);
                      b.type = n.dimension == 2 ? "Coordinates2D" : "Coordinates3D";
                      b.value =
                          ProjectIO.JSON.valueToTree(
                              n.dimension == 2 ? new double[] {0, 0} : new double[] {0, 0, 0});
                      item.inputs.put("coordinates", b);
                    } else item.inputs.remove("coordinates");
                  });
              ImGui.textWrapped(
                  "Default sampling uses incoming coordinates; the selected field applies its own"
                      + " transforms once.");
              if (Set.of("FBM", "RIDGED", "BILLOW", "PING_PONG").contains(item.kind)) {
                parameter(item, "octaves", 4);
                parameter(item, "frequency", 1);
                parameter(item, "lacunarity", 2);
                parameter(item, "gain", .5);
                ImGui.textWrapped(
                    "Octaves reuse the selected field's canonical seed at increasing coordinate"
                        + " frequencies.");
              }
              if (item.kind.equals("OFFSET_SAMPLE"))
                for (String axis : n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"))
                  parameter(item, axis, 0);
              if (item.kind.equals("DERIVATIVE") || item.kind.equals("SMOOTH_FILTER"))
                parameter(item, "spacing", 1);
              if (item.kind.equals("DERIVATIVE"))
                f.choice(
                    "Axis",
                    item.parameters.containsKey("axis")
                        ? item.parameters.get("axis").asText()
                        : "x",
                    n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"),
                    v -> item.parameters.put("axis", ProjectIO.JSON.valueToTree(v)));
              if (item.kind.equals("SMOOTH_FILTER")) {
                parameter(item, "radius", 1);
                parameter(item, "strength", 1);
                f.choice(
                    "Kernel",
                    item.parameters.containsKey("kernel")
                        ? item.parameters.get("kernel").asText()
                        : "BOX",
                    List.of("BOX", "GAUSSIAN"),
                    v -> item.parameters.put("kernel", ProjectIO.JSON.valueToTree(v)));
              }
              if (item.kind.equals("WARP_SAMPLE")) {
                parameter(item, "amplitude", 8);
                parameter(item, "frequency", .01);
                parameter(item, "seedOffset", 0);
              }
            }
            default -> {}
          }
          itemActions(n.processing, i);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      if (ImGui.button("Add Processing")) ImGui.openPopup("processing-add");
      if (ImGui.beginPopup("processing-add")) {
        for (var e : ProcessingRegistry.ITEMS.entrySet())
          if (ImGui.menuItem(e.getKey() + " / " + e.getValue().category())) {
            Item i =
                ProcessingRegistry.create(
                    e.getKey(), uniqueItem(n, e.getKey().toLowerCase(Locale.ROOT)));
            n.processing.add(i);
            if (i.resultType.equals("Scalar")) n.outputBinding = Binding.earlier(i.uuid);
          }
        ImGui.endPopup();
      }
    }
    if (focusedControl != null
        && n.sharedParameters.stream().anyMatch(p -> p.uuid.equals(focusedControl)))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Parameters")) {
      for (var p : n.sharedParameters) {
        ImGui.pushID(p.uuid.toString());
        revealRow(p.uuid);
        f.text("ID", p.id, v -> p.id = v);
        ImGui.text(p.type);
        constantEditor(p.type, p.value, v -> p.value = v);
        if (ImGui.smallButton("Pin Control")) {
          var pin = new Pin();
          pin.targetUuid = p.uuid;
          pin.label = p.id;
          n.pinnedControls.add(pin);
        }
        ImGui.popID();
      }
      if (ImGui.button("Add Parameter")) ImGui.openPopup("new-parameter");
      if (ImGui.beginPopup("new-parameter")) {
        for (String type : List.of("Scalar", "Boolean", "Vector2", "Vector3"))
          if (ImGui.menuItem(type)) {
            var param = new Parameter();
            param.id =
                ResourceTransactions.unique(
                    "parameter", n.sharedParameters.stream().map(v -> v.id).toList());
            param.type = type;
            param.value =
                ProjectIO.JSON.valueToTree(
                    type.equals("Scalar")
                        ? 0
                        : type.equals("Boolean")
                            ? false
                            : type.equals("Vector2")
                                ? new double[] {0, 0}
                                : new double[] {0, 0, 0});
            n.sharedParameters.add(param);
          }
        ImGui.endPopup();
      }
    }
    if (!n.pinnedControls.isEmpty() && ImGui.collapsingHeader("Pinned Controls")) {
      for (var pin : n.pinnedControls) {
        var param =
            n.sharedParameters.stream().filter(v -> v.uuid.equals(pin.targetUuid)).findFirst();
        if (param.isPresent()) {
          ImGui.text(pin.label);
          constantEditor(param.get().type, param.get().value, v -> param.get().value = v);
        }
      }
    }
    if (ImGui.collapsingHeader("Output", ImGuiTreeNodeFlags.DefaultOpen)) {
      String label =
          n.outputBinding.sourceKind.equals("Source Result")
              ? "Raw source"
              : n.processing.stream()
                  .filter(i -> i.uuid.equals(n.outputBinding.itemUuid))
                  .map(i -> i.id)
                  .findFirst()
                  .orElse("Missing result");
      if (ImGui.beginCombo("Scalar output", label)) {
        if (ImGui.selectable("Raw source")) n.outputBinding = Binding.source();
        for (var item : n.processing)
          if (item.enabled && item.resultType.equals("Scalar") && ImGui.selectable(item.id))
            n.outputBinding = Binding.earlier(item.uuid);
        ImGui.endCombo();
      }
    }
  }

  private boolean curveContains(Item item, UUID uuid) {
    if (uuid == null || !item.parameters.containsKey("curve")) return false;
    var points = item.parameters.get("curve").get("points");
    if (points == null) return false;
    for (var point : points)
      if (point.has("uuid") && uuid.toString().equals(point.get("uuid").asText())) return true;
    return false;
  }

  private void algorithmNumber(Noise n, String key, double def) {
    f.number(
        key,
        n.algorithm.parameters.getOrDefault(key, def),
        v -> n.algorithm.parameters.put(key, v));
  }

  private void parameter(Item item, String key, double def) {
    f.number(
        key,
        item.parameters.containsKey(key) ? item.parameters.get(key).asDouble() : def,
        v -> item.parameters.put(key, ProjectIO.JSON.valueToTree(v)));
  }

  private String uniqueItem(Noise n, String base) {
    var names = new ArrayList<String>();
    n.processing.forEach(i -> names.add(i.id));
    n.coordinateTransforms.forEach(i -> names.add(i.id));
    return ResourceTransactions.unique(base, names);
  }

  private void itemActions(List<Item> list, int i) {
    if (ImGui.smallButton("Up") && i > 0) Collections.swap(list, i, i - 1);
    ImGui.sameLine();
    if (ImGui.smallButton("Down") && i < list.size() - 1) Collections.swap(list, i, i + 1);
    ImGui.sameLine();
    if (ImGui.smallButton("Duplicate")) {
      var copy = ResourceTransactions.duplicate(list.get(i), Item.class);
      copy.id = ResourceTransactions.unique(copy.id, list.stream().map(x -> x.id).toList());
      list.add(i + 1, copy);
    }
    ImGui.sameLine();
    if (ImGui.smallButton("Delete")) list.remove(i);
  }

  private void transformControl(Noise noise, Item item, int index, String name, double fallback) {
    ImGui.pushID(name);
    f.bool(
        "Bind " + name,
        item.inputs.containsKey(name),
        v -> {
          if (v)
            item.inputs.put(
                name,
                Binding.constant(
                    item.parameters.containsKey(name)
                        ? item.parameters.get(name).asDouble()
                        : fallback));
          else item.inputs.remove(name);
        });
    if (item.inputs.containsKey(name)) binding(noise, item, index, item.inputs.get(name));
    else parameter(item, name, fallback);
    ImGui.popID();
  }

  private void fieldBinding(Noise n, int index, Binding b) {
    f.choice(
        "Sampled field",
        b.sourceKind,
        List.of("Source Field", "Earlier Field Prefix", "Noise Generator Field"),
        v -> {
          b.sourceKind = v;
          b.type = "Scalar";
          b.itemUuid = null;
          b.noiseGeneratorUuid = null;
        });
    if (b.sourceKind.equals("Earlier Field Prefix")) {
      if (ImGui.beginCombo("Prefix ends at", idFor(n.processing, b.itemUuid))) {
        for (int i = 0; i < index; i++) {
          Item previous = n.processing.get(i);
          if (previous.enabled
              && previous.resultType.equals("Scalar")
              && ImGui.selectable(previous.id)) b.itemUuid = previous.uuid;
        }
        ImGui.endCombo();
      }
      ImGui.textWrapped(
          "Reevaluates this earlier result and its dependencies at each requested location.");
    } else if (b.sourceKind.equals("Noise Generator Field")) {
      noisePicker("Field resource", b.noiseGeneratorUuid, u -> b.noiseGeneratorUuid = u);
      f.choice("Projection", b.projection, List.of("XZ", "XY", "YZ"), v -> b.projection = v);
      f.number("Inserted slice coordinate", b.slice, v -> b.slice = v);
      if (b.noiseGeneratorUuid != null && ImGui.button("Open Referenced Noise Generator")) {
        var obj = EditorState.find(state.project(), b.noiseGeneratorUuid);
        if (obj instanceof Noise ref) state.select("noise" + ref.dimension + "D", ref.uuid);
      }
    }
  }

  private void binding(Noise n, Item item, int index, Binding b) {
    f.choice(
        "Type",
        b.type,
        List.of("Scalar", "Boolean", "Vector2", "Vector3", "Coordinates2D", "Coordinates3D"),
        v -> {
          b.type = v;
          b.sourceKind = "Constant";
          b.value =
              ProjectIO.JSON.valueToTree(
                  v.equals("Boolean")
                      ? false
                      : v.equals("Scalar")
                          ? 0
                          : v.endsWith("2") || v.endsWith("2D")
                              ? new double[] {0, 0}
                              : new double[] {0, 0, 0});
        });
    boolean coordinateStage = n.coordinateTransforms.contains(item);
    f.choice(
        "Source",
        b.sourceKind,
        coordinateStage
            ? List.of(
                "Constant",
                "Shared Parameter",
                "Ambient Value",
                "Earlier Result",
                "Noise Generator Reference")
            : List.of(
                "Constant",
                "Shared Parameter",
                "Ambient Value",
                "Source Result",
                "Earlier Result",
                "Noise Generator Reference"),
        v -> {
          b.sourceKind = v;
          if (v.equals("Constant") && b.value == null) b.value = ProjectIO.JSON.valueToTree(0);
        });
    switch (b.sourceKind) {
      case "Constant" -> constantEditor(b.type, b.value, v -> b.value = v);
      case "Shared Parameter" -> {
        if (ImGui.beginCombo("Parameter", idFor(n.sharedParameters, b.parameterUuid))) {
          for (var p : n.sharedParameters)
            if (p.type.equals(b.type) && ImGui.selectable(p.id)) b.parameterUuid = p.uuid;
          ImGui.endCombo();
        }
      }
      case "Ambient Value" ->
          f.choice(
              "Ambient",
              b.ambientKey == null ? "" : b.ambientKey,
              new ArrayList<>(NoiseCompiler.AMBIENT),
              v -> b.ambientKey = v);
      case "Earlier Result" -> {
        if (ImGui.beginCombo(
            "Result", idFor(coordinateStage ? n.coordinateTransforms : n.processing, b.itemUuid))) {
          for (int i = 0; i < index; i++) {
            var p = (coordinateStage ? n.coordinateTransforms : n.processing).get(i);
            if (p.enabled && p.resultType.equals(b.type) && ImGui.selectable(p.id))
              b.itemUuid = p.uuid;
          }
          ImGui.endCombo();
        }
      }
      case "Noise Generator Reference" -> {
        noisePicker("Resource", b.noiseGeneratorUuid, u -> b.noiseGeneratorUuid = u);
        f.choice("Projection", b.projection, List.of("XZ", "XY", "YZ"), v -> b.projection = v);
        f.number("Slice coordinate", b.slice, v -> b.slice = v);
        if (b.noiseGeneratorUuid != null && ImGui.button("Open Referenced Noise Generator")) {
          var obj = EditorState.find(state.project(), b.noiseGeneratorUuid);
          if (obj instanceof Noise ref) state.select("noise" + ref.dimension + "D", ref.uuid);
        }
      }
      default -> {}
    }
  }

  private void constantEditor(String type, JsonNode value, Consumer<JsonNode> set) {
    if (type.equals("Boolean"))
      f.bool(
          "Value",
          value != null && value.asBoolean(),
          v -> set.accept(ProjectIO.JSON.valueToTree(v)));
    else if (type.equals("Scalar"))
      f.number(
          "Value",
          value == null ? 0 : value.asDouble(),
          v -> set.accept(ProjectIO.JSON.valueToTree(v)));
    else {
      int count = type.endsWith("2") || type.endsWith("2D") ? 2 : 3;
      double[] vec = new double[count];
      for (int i = 0; i < count; i++)
        vec[i] = value != null && value.isArray() && value.size() > i ? value.get(i).asDouble() : 0;
      for (int i = 0; i < count; i++) {
        final int k = i;
        f.number(
            count == 2 ? (i == 0 ? "X" : "Z") : List.of("X", "Y", "Z").get(i),
            vec[i],
            v -> {
              vec[k] = v;
              set.accept(ProjectIO.JSON.valueToTree(vec));
            });
      }
    }
  }

  private static String idFor(List<? extends Identity> list, UUID u) {
    return list.stream()
        .filter(p -> p.uuid.equals(u))
        .map(p -> p.id)
        .findFirst()
        .orElse("Select...");
  }

  public void noisePicker(String label, UUID current, Consumer<UUID> set) {
    var list = new ArrayList<Noise>();
    list.addAll(state.project().noise2D);
    list.addAll(state.project().noise3D);
    if (ImGui.beginCombo(label, idFor(list, current))) {
      if (ImGui.selectable("None")) set.accept(null);
      for (var n : list) if (ImGui.selectable(n.id + " [" + n.dimension + "D]")) set.accept(n.uuid);
      ImGui.endCombo();
    }
  }

  private void fieldPicker(String label, UUID current, Consumer<UUID> set) {
    var list = new ArrayList<Noise>();
    list.addAll(state.project().noise2D);
    list.addAll(state.project().noise3D);
    if (ImGui.beginCombo(label, current == null ? "Placement field" : idFor(list, current))) {
      if (ImGui.selectable("Placement field", current == null)) set.accept(null);
      for (var n : list)
        if (ImGui.selectable(n.id + " [" + n.dimension + "D]", n.uuid.equals(current)))
          set.accept(n.uuid);
      ImGui.endCombo();
    }
  }

  private void placement(Operation op) {
    f.bool("Enabled", op.enabled, v -> op.enabled = v);
    noisePicker("Noise generator", op.noiseGeneratorUuid, v -> op.noiseGeneratorUuid = v);
    f.choice(
        "Interpretation",
        op.interpretation,
        WorldSemantics.INTERPRETATIONS,
        v -> op.interpretation = v);
    Layer layer = EditorState.layerFor(state.project(), op.uuid);
    if (layer == null) return;
    ImGui.text("Layer: " + layer.id);
    if (ImGui.button("Go To Source") && op.noiseGeneratorUuid != null) {
      var obj = EditorState.find(state.project(), op.noiseGeneratorUuid);
      if (obj instanceof Noise n) state.select("noise" + n.dimension + "D", n.uuid);
    }
    ImGui.separator();
    for (String key : SettingsResolver.PROPERTIES) {
      ImGui.pushID(key);
      boolean overridden = op.overrides.containsKey(key);
      f.bool(
          "Override " + AppMetadata.settingLabel(key),
          overridden,
          v -> {
            if (v) {
              JsonNode node = ProjectIO.JSON.valueToTree(layer.defaults);
              op.overrides.put(key, node.get(key).deepCopy());
            } else op.overrides.remove(key);
          });
      Settings effective;
      try {
        effective = SettingsResolver.resolve(layer, op);
      } catch (Exception e) {
        ImGui.textWrapped(e.getMessage());
        ImGui.popID();
        continue;
      }
      ImGui.textDisabled(overridden ? "Placement override" : "Layer default (live)");
      if (overridden) {
        editSetting(effective, key);
        JsonNode node = ProjectIO.JSON.valueToTree(effective);
        op.overrides.put(key, node.get(key).deepCopy());
        if (ImGui.smallButton("Reset to Layer Default")) op.overrides.remove(key);
      } else {
        JsonNode node = ProjectIO.JSON.valueToTree(effective);
        ImGui.textWrapped(AppMetadata.settingLabel(key) + ": " + settingValue(key, node.get(key)));
      }
      ImGui.popID();
    }
  }

  private String settingValue(String key, JsonNode value) {
    if (value == null || value.isNull()) return "None";
    if (key.equals("voxelTypeUuid")) {
      try {
        return idFor(state.project().voxelTypes, UUID.fromString(value.asText()));
      } catch (Exception e) {
        return "Unresolved type";
      }
    }
    if (value.isArray()) return value.size() + " ordered items";
    if (value.isObject())
      return value.has("mapping")
          ? (value.path("enabled").asBoolean()
              ? value.path("mapping").asText() + " field mapping"
              : "Disabled (factor 1)")
          : value.path("kind").asText("Configured condition");
    if (value.isFloatingPointNumber()) return String.format(Locale.ROOT, "%.5f", value.asDouble());
    return value.asText();
  }

  private void settings(Settings s) {
    for (String key : SettingsResolver.PROPERTIES) {
      ImGui.pushID(key);
      editSetting(s, key);
      ImGui.popID();
    }
  }

  private void editSetting(Settings s, String key) {
    try {
      var field = Settings.class.getField(key);
      if (field.getType() == double.class)
        f.number(
            AppMetadata.settingLabel(key),
            field.getDouble(s),
            v -> {
              try {
                field.setDouble(s, v);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
      else if (field.getType() == int.class)
        f.integer(
            AppMetadata.settingLabel(key),
            field.getInt(s),
            v -> {
              try {
                field.setInt(s, v);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
      else if (key.equals("seedOffset"))
        longText(AppMetadata.settingLabel(key), s.seedOffset, v -> s.seedOffset = v);
      else if (key.equals("action"))
        f.choice(
            AppMetadata.settingLabel(key),
            s.action,
            List.of("NO_CHANGE", "SET", "CLEAR"),
            v -> s.action = v);
      else if (key.equals("rounding"))
        f.choice(
            AppMetadata.settingLabel(key),
            s.rounding,
            List.of("FLOOR", "CEIL", "ROUND"),
            v -> s.rounding = v);
      else if (key.startsWith("axis"))
        f.choice(
            AppMetadata.settingLabel(key),
            (String) field.get(s),
            WorldSemantics.AXES,
            v -> {
              try {
                field.set(s, v);
              } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
              }
            });
      else if (key.equals("typeSource"))
        f.choice("Type Source", s.typeSource, WorldSemantics.TYPE_SOURCES, v -> s.typeSource = v);
      else if (key.equals("samplingTransforms")) {
        if (ImGui.treeNode("Ordered Sampling Transforms")) {
          samplingTransforms(s.samplingTransforms);
          ImGui.treePop();
        }
      } else if (key.equals("typeIntervals")) {
        if (ImGui.treeNode("Voxel Type Intervals")) {
          typeIntervals(s.typeIntervals);
          ImGui.treePop();
        }
      } else if (key.equals("probabilityDriver") || key.equals("strengthDriver")) {
        if (ImGui.treeNode(AppMetadata.settingLabel(key))) {
          fieldDriver(key.equals("probabilityDriver") ? s.probabilityDriver : s.strengthDriver);
          ImGui.treePop();
        }
      } else if (key.equals("voxelTypeUuid")) {
        if (s.typeSource.equals("INTERVALS")) ImGui.textDisabled("Voxel targets: interval list");
        else typePicker(AppMetadata.settingLabel(key), s.voxelTypeUuid, v -> s.voxelTypeUuid = v);
      } else if (key.equals("voxelStatePredicate")) {
        if (ImGui.treeNode("Previous Voxel State")) {
          predicate(s.voxelStatePredicate, 0);
          ImGui.treePop();
        }
      } else if (key.equals("fieldGates")) {
        if (ImGui.treeNode("Field Selection Gates")) {
          gate(s.fieldGates, 0);
          ImGui.treePop();
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private void typePicker(String label, UUID uuid, Consumer<UUID> set) {
    if (ImGui.beginCombo(label, idFor(state.project().voxelTypes, uuid))) {
      if (ImGui.selectable("None")) set.accept(null);
      for (var t : state.project().voxelTypes) if (ImGui.selectable(t.id)) set.accept(t.uuid);
      ImGui.endCombo();
    }
  }

  private void predicate(Predicate p, int depth) {
    if (depth > 16) {
      ImGui.text("Maximum editor nesting reached");
      return;
    }
    f.choice(
        "Condition",
        p.kind,
        List.of(
            "ANY",
            "EMPTY",
            "OCCUPIED",
            "TYPE_IS",
            "TYPE_IS_NOT",
            "IN_SET",
            "NOT_IN_SET",
            "AND",
            "OR"),
        v -> p.kind = v);
    if (p.kind.startsWith("TYPE_")) typePicker("Voxel type", p.reference, v -> p.reference = v);
    else if (p.kind.equals("IN_SET") || p.kind.equals("NOT_IN_SET")) {
      if (ImGui.beginCombo("Voxel set", idFor(state.project().voxelSets, p.reference))) {
        for (var set : state.project().voxelSets)
          if (ImGui.selectable(set.id)) p.reference = set.uuid;
        ImGui.endCombo();
      }
    }
    if (p.kind.equals("AND") || p.kind.equals("OR")) {
      for (int i = 0; i < p.children.size(); i++) {
        ImGui.pushID(i);
        if (ImGui.treeNode("Clause " + (i + 1))) {
          predicate(p.children.get(i), depth + 1);
          if (ImGui.smallButton("Remove")) p.children.remove(i);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      if (ImGui.button("Add Clause")) p.children.add(new Predicate());
    }
  }

  private void gate(Gate g, int depth) {
    if (depth > 16) return;
    f.choice(
        "Gate",
        g.kind,
        WorldSemantics.GATES,
        v -> {
          g.kind = v;
          if (v.equals("NOT") && g.children.isEmpty()) g.children.add(new Gate());
        });
    if (g.kind.equals("COMPARE") || g.kind.equals("INTERVAL")) {
      fieldPicker("Field", g.noiseGeneratorUuid, v -> g.noiseGeneratorUuid = v);
      if (g.kind.equals("COMPARE"))
        f.choice(
            "Comparison",
            g.comparison,
            List.of("GE", "GT", "LE", "LT", "EQ", "NE"),
            v -> g.comparison = v);
      f.number("Threshold / minimum", g.threshold, v -> g.threshold = v);
      if (g.kind.equals("INTERVAL")) {
        f.number("Maximum", g.max, v -> g.max = v);
        f.bool("Include minimum", g.includeMin, v -> g.includeMin = v);
        f.bool("Include maximum", g.includeMax, v -> g.includeMax = v);
      }
    }
    if (g.kind.equals("PROBABILITY")) {
      f.number("Gate probability [0,1]", g.probability, v -> g.probability = v);
      longText("Gate seed offset", g.seedOffset, v -> g.seedOffset = v);
      ImGui.textWrapped("Independent deterministic XYZ draw, stable within this gate path.");
    }
    if (g.kind.equals("AND") || g.kind.equals("OR") || g.kind.equals("NOT")) {
      for (int i = 0; i < g.children.size(); i++) {
        ImGui.pushID(i);
        if (smokeWorldSettings != null) ImGui.setNextItemOpen(true, ImGuiCond.Always);
        if (ImGui.treeNode("Gate clause " + i)) {
          gate(g.children.get(i), depth + 1);
          if (ImGui.smallButton("Remove")) g.children.remove(i);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      if ((!g.kind.equals("NOT") || g.children.isEmpty()) && ImGui.button("Add Gate"))
        g.children.add(new Gate());
    }
  }

  private void fieldDriver(FieldDriver d) {
    f.bool("Enabled", d.enabled, v -> d.enabled = v);
    fieldPicker("Field", d.noiseGeneratorUuid, v -> d.noiseGeneratorUuid = v);
    f.choice("Mapping", d.mapping, WorldSemantics.MAPPINGS, v -> d.mapping = v);
    f.number("Input minimum", d.inputMin, v -> d.inputMin = v);
    f.number("Input maximum", d.inputMax, v -> d.inputMax = v);
    f.number("Output at minimum [0,1]", d.outputMin, v -> d.outputMin = v);
    f.number("Output at maximum [0,1]", d.outputMax, v -> d.outputMax = v);
    ImGui.textWrapped(
        "Samples use the placement coordinates. Input clamps to the declared range; the mapped"
            + " factor multiplies the constant. Disabled factor = 1. Use ALL interpretation for a"
            + " parameter-only input.");
  }

  private void typeIntervals(List<TypeInterval> intervals) {
    ImGui.textWrapped(
        "Non-overlapping scalar intervals select occupied types. No match leaves the previous voxel"
            + " unchanged. Shared endpoints may belong to at most one interval.");
    int remove = -1, up = -1, down = -1;
    for (int i = 0; i < intervals.size(); i++) {
      ImGui.pushID(i);
      var r = intervals.get(i);
      if (smokeWorldSettings != null) ImGui.setNextItemOpen(true, ImGuiCond.Always);
      if (ImGui.treeNode(
          "Interval " + (i + 1) + " : " + idFor(state.project().voxelTypes, r.voxelTypeUuid))) {
        f.number("Minimum", r.min, v -> r.min = v);
        f.number("Maximum", r.max, v -> r.max = v);
        f.bool("Include minimum", r.includeMin, v -> r.includeMin = v);
        f.bool("Include maximum", r.includeMax, v -> r.includeMax = v);
        typePicker("Voxel type", r.voxelTypeUuid, v -> r.voxelTypeUuid = v);
        if (i > 0) {
          if (ImGui.smallButton("Up")) up = i;
          ImGui.sameLine();
        }
        if (i + 1 < intervals.size()) {
          if (ImGui.smallButton("Down")) down = i;
          ImGui.sameLine();
        }
        if (ImGui.smallButton("Remove")) remove = i;
        ImGui.treePop();
      }
      ImGui.popID();
    }
    if (remove >= 0) intervals.remove(remove);
    else if (up >= 0) Collections.swap(intervals, up, up - 1);
    else if (down >= 0) Collections.swap(intervals, down, down + 1);
    if (intervals.size() < 256 && ImGui.button("Add Type Interval")) {
      var r = new TypeInterval();
      if (!intervals.isEmpty()) {
        r.min = intervals.getLast().max;
        r.max = r.min + 1;
        r.includeMin = !intervals.getLast().includeMax;
      }
      if (!state.project().voxelTypes.isEmpty())
        r.voxelTypeUuid = state.project().voxelTypes.getFirst().uuid;
      intervals.add(r);
    }
  }

  private void samplingTransforms(List<SamplingTransform> transforms) {
    ImGui.textWrapped(
        "Signed axes, then component scale/offset, then this list in order. Rotations are"
            + " right-handed degrees about the pivot. 2D fields use transformed X/Z.");
    int remove = -1, up = -1, down = -1;
    for (int i = 0; i < transforms.size(); i++) {
      ImGui.pushID(i);
      var t = transforms.get(i);
      if (smokeWorldSettings != null) ImGui.setNextItemOpen(true, ImGuiCond.Always);
      if (ImGui.treeNode("Transform " + (i + 1) + " : " + t.kind)) {
        f.bool("Enabled", t.enabled, v -> t.enabled = v);
        f.choice(
            "Kind",
            t.kind,
            WorldSemantics.TRANSFORMS,
            v -> {
              t.kind = v;
              t.x = t.y = t.z = v.equals("SCALE") ? 1 : 0;
            });
        f.number("X", t.x, v -> t.x = v);
        f.number("Y", t.y, v -> t.y = v);
        f.number("Z", t.z, v -> t.z = v);
        if (!t.kind.equals("TRANSLATE")) {
          f.number("Pivot X", t.pivotX, v -> t.pivotX = v);
          f.number("Pivot Y", t.pivotY, v -> t.pivotY = v);
          f.number("Pivot Z", t.pivotZ, v -> t.pivotZ = v);
          if (t.kind.equals("ROTATE"))
            f.choice(
                "Axis order",
                t.rotationOrder,
                WorldSemantics.ROTATION_ORDERS,
                v -> t.rotationOrder = v);
        }
        if (i > 0) {
          if (ImGui.smallButton("Up")) up = i;
          ImGui.sameLine();
        }
        if (i + 1 < transforms.size()) {
          if (ImGui.smallButton("Down")) down = i;
          ImGui.sameLine();
        }
        if (ImGui.smallButton("Duplicate") && transforms.size() < 64)
          transforms.add(
              i + 1,
              ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(t), SamplingTransform.class));
        ImGui.sameLine();
        if (ImGui.smallButton("Remove")) remove = i;
        ImGui.treePop();
      }
      ImGui.popID();
    }
    if (remove >= 0) transforms.remove(remove);
    else if (up >= 0) Collections.swap(transforms, up, up - 1);
    else if (down >= 0) Collections.swap(transforms, down, down + 1);
    if (transforms.size() < 64 && ImGui.button("Add Sampling Transform"))
      transforms.add(new SamplingTransform());
  }

  private void longText(String label, long value, java.util.function.LongConsumer set) {
    f.text(
        label,
        Long.toString(value),
        v -> {
          try {
            set.accept(Long.parseLong(v));
          } catch (NumberFormatException ignored) {
          }
        });
  }
}
