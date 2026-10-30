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
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(480, 720);
  final PanelDockControls docking = new PanelDockControls("Properties");
  private final EditorState state;
  private final UiFields f = new UiFields(this::noiseChanged);
  private long noiseVersion;
  private final NoiseDraftIndex.Cache noiseIndex = new NoiseDraftIndex.Cache();
  private final NoiseItemBrowser noiseBrowser = new NoiseItemBrowser();
  private final DraftChoicePicker draftChoices = new DraftChoicePicker();
  private NoiseEditDialog noiseEdit;
  private ExpressionEditDialog expressionDialog;
  private SearchNavigation referenceNavigation;
  private boolean referencePrompt;
  private String authoringMessage = "";
  private final Map<String, String> inputNames = new HashMap<>();
  private final Map<String, List<DraftChoicePicker.Choice>> catalogues = new HashMap<>();
  private final Map<String, float[]> referenceButtons = new HashMap<>();
  public Consumer<UUID> findReferences = ignored -> {};
  private static final List<DraftChoicePicker.Choice> ALGORITHMS =
      NoiseAlgorithms.KEYS.stream()
          .map(key -> new DraftChoicePicker.Choice(key, key, "Source algorithm"))
          .toList();
  private static final List<DraftChoicePicker.Choice> OPERATIONS =
      ProcessingRegistry.ITEMS.entrySet().stream()
          .map(
              e ->
                  new DraftChoicePicker.Choice(
                      e.getKey(),
                      e.getKey(),
                      e.getValue().category() + " / " + e.getValue().result()))
          .toList();
  private static final List<DraftChoicePicker.Choice> TRANSFORMS =
      CreationRegistry.TRANSFORMS.stream()
          .map(key -> new DraftChoicePicker.Choice(key, key, "Coordinates & Transforms"))
          .toList();

  private void noiseChanged() {
    noiseVersion++;
  }

  private NoiseDraftIndex index(Noise noise) {
    return noiseIndex.get(noise, noiseVersion);
  }

  private final ResourcePicker resourcePicker;
  private MembershipDialog membershipDialog;
  private final ExpressionFeedback expressionFeedback = new ExpressionFeedback();
  private UUID last;
  private Object lastDraft;
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
    if (!smokeWorldDrawn.containsAll(
        Set.of("Transforms", "Types", "Drivers and gates", "Numeric parameters")))
      throw new IllegalStateException("World-use controls were not rendered: " + smokeWorldDrawn);
    System.out.println("WORLD_USE_CONTROLS_NATIVE_PASS");
    System.out.println("NUMERIC_PARAMETER_CONTROLS_NATIVE_PASS");
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
        case "Numeric parameters" -> parameterDrivers(smokeWorldSettings);
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

  private boolean smokeExpressionMode, smokeExpressionDrawn;

  public void smokeExpression(boolean show) {
    smokeExpressionMode = show;
    if (show) {
      var n = state.project().noise2D.getFirst();
      var item =
          n.processing.stream().filter(i -> i.kind.equals("EXPRESSION")).findFirst().orElseThrow();
      goToControl(n.uuid, item.uuid);
    }
  }

  public void assertSmokeExpressionRendered() {
    if (!smokeExpressionDrawn)
      throw new IllegalStateException("Expression feedback was not rendered");
    System.out.println("COMPILED_EXPRESSION_FEEDBACK_NATIVE_PASS");
  }

  private UUID focusedControl;
  private EditorEvents.Target navigationTarget;
  private Object navigationDraft;
  private boolean bindingFocusSubmitted;
  private final PropertyFocus propertyFocus = new PropertyFocus();
  private final NoiseInputSelection inputSelection = new NoiseInputSelection();
  private final PinBrowser pinBrowser = new PinBrowser();
  private final UiFields pinFields = new UiFields(this::noiseChanged);
  private UUID pinFocusControl;
  private Pin pinFocusPin;
  private String pinFocusKey, pinMessage = "";
  private final Map<String, float[]> pinCenters = new HashMap<>();

  float[] probePinControl(String key) {
    return pinCenters.get(key);
  }

  Pin probeSelectedPin() {
    return pinBrowser.selected();
  }

  List<DraftChoicePicker.Choice> probePinRows() {
    return pinBrowser.rows();
  }

  void probePinGroup(String key) {
    pinBrowser.showGroup(key);
  }

  void probePinPanel() {
    pinnedControls((Noise) state.draft);
  }

  float[] probePinOption(String key) {
    return draftChoices.option(key);
  }

  private void pinRect(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    pinCenters.put(key, new float[] {a.x + Math.min(80, (b.x - a.x) * .25f), (a.y + b.y) / 2});
  }

  private void cancelPinFocus() {
    propertyFocus.cancel();
    pinFocusPin = null;
    pinFocusKey = null;
    pinMessage = "";
  }

  private boolean searchScroll;
  private WorldSettingNavigation.Route worldRoute;
  private String worldSettingPath;
  private final Map<String, float[]> worldControlCenters = new HashMap<>();

  float[] probeWorldControl(String key) {
    return worldControlCenters.get(key);
  }

  private void worldControl(String key, Runnable draw) {
    worldControl(key, draw, () -> ImGui.isItemActive() || ImGui.isItemFocused());
  }

  private void worldControl(String key, Runnable draw, BooleanSupplier focused) {
    propertyControl(null, key, draw, focused);
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    worldControlCenters.put(
        key, new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
  }

  private void settingControl(String leaf, Runnable draw) {
    settingControl(leaf, draw, () -> ImGui.isItemActive() || ImGui.isItemFocused());
  }

  private void settingControl(String leaf, Runnable draw, BooleanSupplier focused) {
    if (worldSettingPath == null) {
      draw.run();
      return;
    }
    worldControl(
        "settings/" + worldSettingPath + (leaf.isEmpty() ? "" : "/" + leaf), draw, focused);
  }

  private boolean settingTree(String label) {
    String key = "settings/" + worldSettingPath;
    if (worldRoute != null
        && navigationDraft == state.draft
        && (worldRoute.control().equals(key) || worldRoute.control().startsWith(key + "/"))
        && propertyFocus.matches(null, worldRoute.control()))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    boolean[] open = {false};
    settingControl("", () -> open[0] = ImGui.treeNode(label));
    return open[0];
  }

  private void nestedSetting(String suffix, Runnable draw) {
    String previous = worldSettingPath;
    worldSettingPath = previous == null ? null : previous + "/" + suffix;
    try {
      draw.run();
    } finally {
      worldSettingPath = previous;
    }
  }

  public boolean goToProblem(EditorEvents.Target target) {
    if (searchBlocked()) {
      state.status = "Finish or cancel the open property editor before following another control.";
      return false;
    }
    if (!Objects.equals(state.selected, target.resource())) {
      var result = referenceNavigation.request(target);
      if (result == SearchNavigation.Result.NEEDS_DECISION) {
        referencePrompt = true;
        return true;
      }
      if (result != SearchNavigation.Result.NAVIGATED) return false;
    }
    if (!state.navigate(target)) return false;
    focusedControl = target.control();
    navigationTarget = target;
    navigationDraft = state.draft;
    expressionSelection.cancel();
    worldRoute =
        WorldSettingNavigation.resolve(
            state.draft,
            state.draft instanceof Operation op
                ? EditorState.layerFor(state.project(), op.uuid)
                : null,
            target);
    var focusTarget =
        worldRoute == null
            ? target
            : new EditorEvents.Target(
                target.project(),
                target.resource(),
                null,
                worldRoute.control(),
                target.pointer(),
                target.settings());
    pinFocusKey = null;
    pinFocusPin = null;
    pinMessage = "";
    if (state.draft instanceof Noise noise) {
      String pinKey = PinBrowser.navigation(target);
      if (pinKey != null) {
        var previousPin = pinBrowser.selected();
        if (pinBrowser.synchronize(noise, noiseVersion)) pinFields.clear();
        boolean precise = target.control() != null && pinBrowser.selectTarget(target.control());
        if (pinBrowser.selected() != previousPin) pinFields.clear();
        pinFocusControl = precise ? target.control() : null;
        pinFocusPin = precise ? pinBrowser.selected() : null;
        pinFocusKey = precise ? pinKey : "pins";
        if (!precise)
          pinMessage =
              "Choose the current pin. Historical pin positions and duplicate target references do"
                  + " not select one automatically.";
        focusTarget =
            new EditorEvents.Target(
                target.project(), target.resource(), pinFocusControl, pinFocusKey, "", false);
        focusedControl = null;
      }
    }
    propertyFocus.request(focusTarget, state.draft);
    bindingFocusSubmitted = false;
    focusedBinding = null;
    if (state.draft instanceof Noise noise
        && target.control() != null
        && target.parameter() != null
        && target.parameter().startsWith("inputs/"))
      for (var items : List.of(noise.processing, noise.coordinateTransforms))
        for (var item : items)
          if (item.uuid.equals(target.control()))
            inputSelection.select(noise, item, target.parameter().substring(7));
    expressionFocusSubmitted = false;
    searchScroll = true;
    if (state.draft instanceof Noise noise) {
      var route = CurveNavigation.resolve(noise, target);
      if (route != null) {
        try {
          openCurve(route.item(), route.point());
          curve.focusControl(route.point(), route.field());
        } catch (RuntimeException invalid) {
          state.status = "Curve target cannot be opened: " + invalid.getMessage();
          return false;
        }
      }
    }
    return true;
  }

  public boolean searchBlocked() {
    return curve != null
        || membershipDialog != null
        || noiseEdit != null
        || expressionDialog != null
        || referenceNavigation.pending();
  }

  public boolean revealSearch(EditorEvents.Target target) {
    return goToProblem(target);
  }

  private void openCurve(Item item, UUID point) {
    if (curve != null) {
      state.status = "Finish or cancel the open Curve Editor before opening another curve.";
      return;
    }
    var publication = new CurveDraftEdit(state, (Noise) state.draft, item);
    var value =
        item.parameters.containsKey("curve")
            ? ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class)
            : new Curve();
    var dialog =
        new CurveDialog(
            value,
            state,
            publication,
            () -> {
              try {
                f.clear();
              } finally {
                noiseChanged();
              }
            });
    if (point != null && !dialog.selectPoint(point))
      throw new IllegalArgumentException("Curve point no longer exists");
    curve = dialog;
  }

  Set<UUID> searchCurveSelection() {
    return curve == null ? Set.of() : curve.selectedPoints();
  }

  void closeSearchCurve() {
    if (curve != null) curve.cancel();
    curve = null;
  }

  private void revealSearchField(UUID control, String parameter) {
    if (searchScroll
        && navigationTarget != null
        && Objects.equals(navigationTarget.resource(), state.selected)
        && Objects.equals(navigationTarget.control(), control)
        && Objects.equals(navigationTarget.parameter(), parameter)) {
      ImGui.setScrollHereY(.25f);
      searchScroll = false;
      ThemeStyle.text("Information", "Search target: " + parameter);
    }
  }

  private void propertyControl(UUID control, String parameter, Runnable draw) {
    propertyControl(control, parameter, draw, () -> ImGui.isItemActive() || ImGui.isItemFocused());
  }

  private void propertyControl(
      UUID control, String parameter, Runnable draw, BooleanSupplier acknowledged) {
    propertyFocus.synchronize(state.project().project.uuid, state.selected, state.draft);
    boolean targeted = !searchBlocked() && propertyFocus.matches(control, parameter);
    boolean submittedNow = targeted && propertyFocus.submit();
    if (submittedNow) ImGui.setKeyboardFocusHere();
    draw.run();
    if (targeted) {
      if (acknowledged.getAsBoolean()) {
        ImGui.setScrollHereY(.25f);
        propertyFocus.acknowledge(control + ":" + parameter);
        searchScroll = false;
      } else if (!submittedNow && ImGui.isMouseClicked(0)
          || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        propertyFocus.cancel();
      } else ImGui.setScrollHereY(.25f);
    }
  }

  String probeFocusedProperty() {
    return propertyFocus.acknowledged();
  }

  private void resultType(Item item, List<String> choices, Consumer<String> update) {
    propertyControl(
        item.uuid, "resultType", () -> f.choice("Result type", item.resultType, choices, update));
  }

  private void parameterChoice(
      Item item, String key, String label, List<String> choices, String fallback) {
    propertyControl(
        item.uuid,
        "parameters/" + key,
        () ->
            f.choice(
                label,
                item.parameters.containsKey(key) ? item.parameters.get(key).asText() : fallback,
                choices,
                value -> item.parameters.put(key, ProjectIO.JSON.valueToTree(value))));
  }

  public void goToControl(UUID resource, UUID control) {
    if (searchBlocked()) {
      state.status = "Finish or cancel the open property editor before following another control.";
      return;
    }
    if (!(EditorState.find(state.project(), resource) instanceof Noise)) return;
    var target =
        new EditorEvents.Target(state.project().project.uuid, resource, control, null, "", false);
    var result = referenceNavigation.request(target);
    if (result == SearchNavigation.Result.NEEDS_DECISION) referencePrompt = true;
    else if (result == SearchNavigation.Result.NAVIGATED) goToProblem(target);
    else authoringMessage = "The requested control is no longer available.";
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
    if (navigationTarget != null && uuid.equals(navigationTarget.control()))
      ThemeStyle.text(
          "Information",
          "Problem: " + Objects.requireNonNullElse(navigationTarget.parameter(), "control"));
  }

  public boolean curveFocused() {
    return curve != null && curve.focused();
  }

  String navigationWindow() {
    return curve == null ? "Properties" : "Curve Editor";
  }

  public void curveShortcut(String action) {
    if (curve != null) curve.shortcut(action);
  }

  public PropertyPanel(EditorState s) {
    state = s;
    resourcePicker = new ResourcePicker(s);
    referenceNavigation = new SearchNavigation(s);
  }

  public void draw() {
    seedCenters.clear();
    worldControlCenters.clear();
    parameterCenters.clear();
    bindingRects.clear();
    sourceControlCenters.clear();
    sourceControlKeys.clear();
    propertyFocus.synchronize(state.project().project.uuid, state.selected, state.draft);
    if (worldRoute != null) {
      if (navigationDraft != state.draft) worldRoute = null;
      else if (propertyFocus.matches(null, worldRoute.control())) {
        var current =
            WorldSettingNavigation.resolve(
                state.draft,
                state.draft instanceof Operation op
                    ? EditorState.layerFor(state.project(), op.uuid)
                    : null,
                navigationTarget);
        if (current == null || !current.control().equals(worldRoute.control())) {
          propertyFocus.cancel();
          worldRoute = null;
        }
      }
    }
    resourcePicker.synchronize();
    windowLayout.prepareExisting();
    docking.prepare();
    boolean visible = ImGui.begin("Properties");
    UiScale.window(state.user, "Properties");
    windowLayout.observe();
    DialogLayout.observe(state, "Properties");
    if (visible) {
      if (probeSeedTop) {
        ImGui.setScrollY(0);
        probeSeedTop = false;
      }
      docking.draw();
      if (!Objects.equals(last, state.selected) || lastDraft != state.draft) {
        last = state.selected;
        lastDraft = state.draft;
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
        if (navigationTarget != null
            && Objects.equals(navigationTarget.resource(), state.selected)) {
          ThemeStyle.text("Information", "Problem location");
          ImGui.textWrapped(
              Objects.requireNonNullElse(navigationTarget.parameter(), "Resource")
                  + "  "
                  + navigationTarget.pointer());
          if (ImGui.smallButton("Clear Location")) {
            navigationTarget = null;
            worldRoute = null;
            expressionSelection.cancel();
            searchScroll = false;
            propertyFocus.cancel();
          }
        }
        seedProblems();
        if (DialogLayout.button("Apply")) state.applyDraft();
        f.tooltip("Apply validates this edit and commits one undoable transaction.");
        DialogLayout.sameLine("Revert");
        if (DialogLayout.button("Revert")) {
          state.refreshDraft();
          f.clear();
        }
        f.tooltip("Revert reloads the selected resource and discards its private edits.");
        if (!state.issues.isEmpty()) {
          ThemeStyle.text("Error", "! " + state.issues.size() + " validation issue(s)");
          for (int i = 0; i < Math.min(8, state.issues.size()); i++) {
            var d = state.issues.get(i);
            ImGui.textWrapped(d.toString());
            var target = EditorEvents.Target.diagnostic(state.project().project.uuid, d);
            if (state.canNavigate(target)
                && !target.settings()
                && ImGui.smallButton("Go To Problem##inline" + i)) goToProblem(target);
          }
          if (state.issues.size() > 8)
            ImGui.textDisabled("More results are available in View > Validate Project.");
        }
        ImGui.separator();
        if (state.draft instanceof Identity r)
          propertyControl(null, "id", () -> f.text("ID", r.id, v -> r.id = v));
        if (state.draft instanceof Noise n) noise(n);
        else if (state.draft instanceof Layer l) {
          int stage = 0;
          for (int i = 0; i < state.project().worldGenerator.layers.size(); i++)
            if (state.project().worldGenerator.layers.get(i).uuid.equals(l.uuid)) stage = i + 1;
          ImGui.text("Layer execution order: " + stage);
          ImGui.textWrapped(
              "Folder placement and tree order are organizational. Use Run Layer Earlier/Later in"
                  + " Resources to change execution.");
          worldControl("enabled", () -> f.bool("Enabled", l.enabled, v -> l.enabled = v));
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
          ImGui.text(m.voxelTypeUuids.size() + " member reference(s)");
          if (ImGui.button("Edit Members...")) membershipDialog = new MembershipDialog(state, m);
          ImGui.textWrapped(
              "Membership editing offers a temporary searchable selection with Apply/Cancel.");
        } else if (state.draft instanceof Folder folder) {
          ImGui.text("Organizational folder: " + folder.rootKind);
          ImGui.textWrapped(
              "Folder placement never changes generation or adds resource inheritance.");
        }
      }
    }
    ImGui.end();
    if (curve != null) {
      smokeCurveDrawn = true;
      if (curve.draw()) curve = null;
    }
    if (membershipDialog != null && membershipDialog.draw()) membershipDialog = null;
    if (noiseEdit != null && noiseEdit.draw()) noiseEdit = null;
    drawExpressionDialog();
    drawReferenceGuard();
    drawSmokeWorldControls();
  }

  private void noise(Noise n) {
    String sourceTarget =
        navigationTarget != null && navigationDraft == state.draft
            ? DiagnosticNavigation.controlParameter(navigationTarget)
            : null;
    ImGui.text("Dimension: " + n.dimension + "D  |  Output: Scalar");
    if (!authoringMessage.isEmpty()) ImGui.textWrapped(authoringMessage);
    if (navigationTarget != null
        && navigationTarget.control() == null
        && Objects.equals(navigationTarget.resource(), state.selected)
        && !Objects.equals(navigationTarget.parameter(), "outputBinding"))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Source", ImGuiTreeNodeFlags.DefaultOpen)) {
      propertyControl(
          null,
          "algorithm",
          () ->
              draftChoices.draw(
                  state.metadata.label("noise.algorithm", "Algorithm"),
                  n.algorithm.key,
                  catalogue(n, "algorithm", ALGORITHMS),
                  null,
                  value -> {
                    n.algorithm.key = value;
                    noiseChanged();
                  }),
          draftChoices::focused);
      metadataTooltip("noise.algorithm");
      ImGui.textWrapped(NoiseAlgorithmRegistry.get(n.algorithm.key).semantics());
      revealSearchField(null, "algorithm");
      propertyControl(
          null,
          "seedOffset",
          () ->
              longText(
                  state.metadata.label("noise.seedOffset", "Seed offset"),
                  n.seedOffset,
                  v -> n.seedOffset = v));
      metadataTooltip("noise.seedOffset");
      revealSearchField(null, "seedOffset");
      sourceControlKeys.clear();
      for (var control : NoiseAlgorithmRegistry.parameters(n.algorithm.key, n.dimension)) {
        boolean targeted =
            navigationTarget != null
                && ("algorithm/parameters/" + control.key()).equals(sourceTarget);
        if (control.visible(n.algorithm) || targeted) algorithmParameter(n, control);
      }
      if (navigationTarget != null
          && sourceTarget != null
          && sourceTarget.startsWith("algorithm/parameters/")) {
        String key = sourceTarget.substring("algorithm/parameters/".length());
        if (!sourceControlKeys.contains(key) && n.algorithm.parameters.containsKey(key)) {
          try {
            ImGui.textWrapped("Stored inactive parameter requires repair for legacy validation.");
            algorithmParameter(n, NoiseAlgorithmRegistry.parameter(key));
          } catch (NoiseAlgorithmRegistry.Problem ignored) {
          }
        }
      }
      long storedInactive =
          n.algorithm.parameters.keySet().stream()
              .filter(
                  key ->
                      NoiseAlgorithmRegistry.parameters(n.algorithm.key, n.dimension).stream()
                          .noneMatch(p -> p.key().equals(key)))
              .count();
      if (storedInactive > 0)
        ImGui.textDisabled(storedInactive + " known inactive source parameters retained.");
    }
    if (focusedControl != null
        && n.coordinateTransforms.stream().anyMatch(i -> i.uuid.equals(focusedControl)))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Coordinates & Transforms")) {
      for (int i :
          noiseBrowser.draw(
              n.uuid,
              index(n),
              NoiseDraftIndex.Section.TRANSFORMS,
              focusedControl,
              (row, enabled) ->
                  requestNoiseEdit(
                      n,
                      NoiseAuthoring.Request.simple(
                          enabled ? NoiseAuthoring.Action.ENABLE : NoiseAuthoring.Action.DISABLE,
                          row.uuid())))) {
        if (noiseBrowser.consumeSelectionChanged()) {
          f.clear();
          inputNames.clear();
        }
        if (noiseBrowser.consumeUserSelectionChanged()) {
          expressionSelection.cancel();
          searchScroll = false;
          propertyFocus.chooseControl(n.coordinateTransforms.get(i).uuid);
        }
        var item = n.coordinateTransforms.get(i);
        ImGui.pushID(item.uuid.toString());
        focusRow(item.uuid);
        if (ImGui.treeNode(
            "["
                + i
                + "] "
                + item.id
                + "  "
                + state.metadata.label("transform." + item.kind, item.kind)
                + "###item-properties")) {
          revealRow(item.uuid);
          f.bool(
              "Enabled",
              item.enabled,
              v ->
                  requestNoiseEdit(
                      n,
                      NoiseAuthoring.Request.simple(
                          v ? NoiseAuthoring.Action.ENABLE : NoiseAuthoring.Action.DISABLE,
                          item.uuid)));
          propertyControl(item.uuid, "id", () -> f.text("ID", item.id, v -> item.id = v));
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
              parameterChoice(
                  item,
                  "rotationOrder",
                  "Rotation order",
                  List.of("XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX"),
                  "XYZ");
            }
            ImGui.textWrapped("Angles are degrees; positive Y rotation turns +X toward +Z.");
          } else if (item.kind.equals("DOMAIN_WARP")) {
            f.bool(
                "Bind amplitude",
                item.inputs.containsKey("amplitude"),
                value -> {
                  if (value)
                    item.inputs.put(
                        "amplitude",
                        Binding.constant(
                            item.parameters
                                .getOrDefault("amplitude", ProjectIO.JSON.valueToTree(8))
                                .asDouble()));
                  else item.inputs.remove("amplitude");
                });
            if (item.inputs.containsKey("amplitude"))
              binding(n, item, i, item.inputs.get("amplitude"));
            warpControls(n, item, i, true);

          } else
            for (String axis : n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"))
              transformControl(n, item, i, axis, item.kind.equals("SCALE") ? 1 : 0);
          if (Set.of("ROTATE", "SCALE").contains(item.kind))
            for (String axis : n.dimension == 2 ? List.of("X", "Z") : List.of("X", "Y", "Z"))
              transformControl(n, item, i, "pivot" + axis, 0);
          itemActions(n, n.coordinateTransforms, i);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      draftChoices.draw(
          "Add Transform",
          null,
          catalogue(n, "transform", TRANSFORMS),
          null,
          key -> {
            var item =
                EditorCreations.transform(
                    state.metadata, n.dimension, key, uniqueItem(n, key.toLowerCase(Locale.ROOT)));
            n.coordinateTransforms.add(item);
            noiseChanged();
            noiseBrowser.select(NoiseDraftIndex.Section.TRANSFORMS, item.uuid);
          });
    }
    if (focusedControl != null
        && n.processing.stream()
            .anyMatch(i -> i.uuid.equals(focusedControl) || curveContains(i, focusedControl)))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Ordered Processing", ImGuiTreeNodeFlags.DefaultOpen)) {
      ImGui.textDisabled("Global evaluation order is shown in [brackets]");
      UUID itemFocus = focusedControl;
      if (itemFocus != null)
        for (var item : n.processing)
          if (curveContains(item, itemFocus)) {
            itemFocus = item.uuid;
            break;
          }
      for (int i :
          noiseBrowser.draw(
              n.uuid,
              index(n),
              NoiseDraftIndex.Section.PROCESSING,
              itemFocus,
              (row, enabled) ->
                  requestNoiseEdit(
                      n,
                      NoiseAuthoring.Request.simple(
                          enabled ? NoiseAuthoring.Action.ENABLE : NoiseAuthoring.Action.DISABLE,
                          row.uuid())))) {
        if (noiseBrowser.consumeSelectionChanged()) {
          f.clear();
          inputNames.clear();
        }
        if (noiseBrowser.consumeUserSelectionChanged()) {
          expressionSelection.cancel();
          searchScroll = false;
          propertyFocus.chooseControl(n.processing.get(i).uuid);
        }
        var item = n.processing.get(i);
        ImGui.pushID(item.uuid.toString());
        if (focusedControl != null && curveContains(item, focusedControl))
          focusedControl = item.uuid;
        focusRow(item.uuid);
        if (ImGui.treeNode(
            "["
                + i
                + "] "
                + item.id
                + "  "
                + state.metadata.label("processing." + item.kind, item.kind)
                + (item.enabled ? "" : " (disabled)")
                + "###item-properties")) {
          revealRow(item.uuid);
          propertyControl(item.uuid, "id", () -> f.text("ID", item.id, v -> item.id = v));
          f.bool(
              "Enabled",
              item.enabled,
              v ->
                  requestNoiseEdit(
                      n,
                      NoiseAuthoring.Request.simple(
                          v ? NoiseAuthoring.Action.ENABLE : NoiseAuthoring.Action.DISABLE,
                          item.uuid)));
          ImGui.textDisabled(
              ProcessingRegistry.ITEMS.containsKey(item.kind)
                  ? ProcessingRegistry.ITEMS.get(item.kind).category()
                  : "Unknown operation");
          if (item.kind.equals("CONSTANT"))
            resultType(
                item,
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
            resultType(
                item,
                List.of(
                    "Scalar", "Boolean", "Vector2", "Vector3", "Coordinates2D", "Coordinates3D"),
                v -> item.resultType = v);
          if (Set.of("COORDINATES_TO_VECTOR", "VECTOR_ROTATE").contains(item.kind))
            resultType(item, List.of("Vector2", "Vector3"), v -> item.resultType = v);
          if (item.kind.equals("VECTOR_TO_COORDINATES"))
            resultType(item, List.of("Coordinates2D", "Coordinates3D"), v -> item.resultType = v);
          if (item.kind.equals("EXPRESSION")) expressionControls(n, item);
          if (java.util.Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE").contains(item.kind))
            resultType(item, List.of("Scalar", "Vector2", "Vector3"), v -> item.resultType = v);
          if (item.kind.equals("NORMALIZE"))
            resultType(item, List.of("Vector2", "Vector3"), v -> item.resultType = v);
          for (var e : selectedInputs(item)) {
            ImGui.pushID(e.getKey());
            if (inputNavigation(item, e.getKey())) ImGui.setNextItemOpen(true, ImGuiCond.Always);
            if (ImGui.treeNode(
                "Input: " + e.getKey() + " [" + e.getValue().type + "]###input-properties")) {
              if ((e.getKey().equals("field") && ProcessingRegistry.SAMPLED.contains(item.kind))
                  || item.kind.equals("WARP_SAMPLE") && WarpRegistry.component(e.getKey()))
                fieldBinding(n, i, e.getValue());
              else binding(n, item, i, e.getValue());
              if (item.kind.equals("EXPRESSION")) namedInput(item, e.getKey());
              if (item.kind.equals("EXPRESSION") && ImGui.smallButton("Remove Named Input")) {
                item.inputs.remove(e.getKey());
                noiseChanged();
              }
              ImGui.treePop();
            }
            ImGui.popID();
          }
          if (item.kind.equals("EXPRESSION") && ImGui.button("Add Named Input")) {
            String name = "input";
            int count = 1;
            while (item.inputs.containsKey(name)) name = "input" + (count++);
            item.inputs.put(name, Binding.constant(0));
            noiseChanged();
          }
          if (ProcessingExtensions.DETAILS.containsKey(item.kind)) extensionControls(n, item);
          switch (item.kind) {
            case "CONSTANT" ->
                constantEditor(
                    item.resultType,
                    item.parameters.get("value"),
                    v -> item.parameters.put("value", v),
                    (key, draw) -> propertyControl(item.uuid, "parameters/" + key, draw));
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
                parameterChoice(item, "axes", "Retained axes", List.of("XZ", "XY", "YZ"), "XZ");
            case "VECTOR_ROTATE" -> {
              parameter(item, "angle", 0);
              parameterChoice(item, "axis", "Rotation axis", List.of("x", "y", "z"), "x");
            }
            case "COORDINATE" -> {
              parameterChoice(
                  item,
                  "axis",
                  "Axis",
                  n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"),
                  "x");
            }
            case "CURVE" -> {
              if (ImGui.button("Edit Curve"))
                try {
                  openCurve(item, null);
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
                "WARP_SAMPLE",
                "GRADIENT",
                "LAPLACIAN",
                "MIN_FILTER",
                "MAX_FILTER",
                "MEDIAN_FILTER" -> {
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
                parameterChoice(
                    item,
                    "axis",
                    "Axis",
                    n.dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"),
                    "x");
              if (item.kind.equals("SMOOTH_FILTER")) {
                parameter(item, "radius", 1);
                parameter(item, "strength", 1);
                parameterChoice(item, "kernel", "Kernel", List.of("BOX", "GAUSSIAN"), "BOX");
              }
              if (item.kind.equals("WARP_SAMPLE")) {
                warpControls(n, item, i, false);
              }
            }
            default -> {}
          }
          itemActions(n, n.processing, i);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      draftChoices.draw(
          "Add Processing",
          null,
          catalogue(n, "processing", OPERATIONS),
          null,
          key -> {
            var item =
                EditorCreations.processing(
                    state.metadata, key, uniqueItem(n, key.toLowerCase(Locale.ROOT)), n.dimension);
            n.processing.add(item);
            if (item.resultType.equals("Scalar")) n.outputBinding = Binding.earlier(item.uuid);
            noiseChanged();
            noiseBrowser.select(NoiseDraftIndex.Section.PROCESSING, item.uuid);
          });
    }
    if (focusedControl != null
        && n.sharedParameters.stream().anyMatch(p -> p.uuid.equals(focusedControl)))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Parameters")) {
      for (int parameterIndex :
          noiseBrowser.draw(n.uuid, index(n), NoiseDraftIndex.Section.PARAMETERS, focusedControl)) {
        if (noiseBrowser.consumeSelectionChanged()) {
          f.clear();
          inputNames.clear();
        }
        if (noiseBrowser.consumeUserSelectionChanged()) {
          expressionSelection.cancel();
          searchScroll = false;
          propertyFocus.chooseControl(n.sharedParameters.get(parameterIndex).uuid);
        }
        var p = n.sharedParameters.get(parameterIndex);
        ImGui.pushID(p.uuid.toString());
        revealRow(p.uuid);
        propertyControl(p.uuid, "id", () -> f.text("ID", p.id, v -> p.id = v));
        ImGui.text(p.type);
        constantEditor(
            p.type,
            p.value,
            v -> p.value = v,
            (key, draw) -> propertyControl(p.uuid, "parameters/" + key, draw));
        if (ImGui.smallButton("Pin Control")) {
          var pin = new Pin();
          pin.targetUuid = p.uuid;
          pin.label = p.id;
          if (n.pinnedControls.stream().noneMatch(existing -> existing.targetUuid.equals(p.uuid)))
            n.pinnedControls.add(pin);
          noiseChanged();
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Duplicate Parameter"))
          requestNoiseEdit(
              n, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.DUPLICATE_PARAMETER, p.uuid));
        ImGui.sameLine();
        if (ImGui.smallButton("Delete Parameter..."))
          requestNoiseEdit(
              n, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.DELETE_PARAMETER, p.uuid));
        dependencies(n, p.uuid);
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
            noiseChanged();
            noiseBrowser.select(NoiseDraftIndex.Section.PARAMETERS, param.uuid);
          }
        ImGui.endPopup();
      }
    }
    pinnedControls(n);
    if (navigationTarget != null
        && Objects.equals(navigationTarget.resource(), state.selected)
        && "outputBinding".equals(navigationTarget.parameter()))
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Output", ImGuiTreeNodeFlags.DefaultOpen)) {
      revealSearchField(null, "outputBinding");
      propertyControl(
          null,
          "outputBinding",
          () ->
              draftChoices.draw(
                  "Scalar output",
                  n.outputBinding.sourceKind.equals("Source Result")
                      ? null
                      : Objects.toString(n.outputBinding.itemUuid, null),
                  index(n).choices("output", null, "Scalar"),
                  "Raw source",
                  value -> {
                    n.outputBinding =
                        value == null ? Binding.source() : Binding.earlier(UUID.fromString(value));
                    noiseChanged();
                  }),
          draftChoices::focused);
    }
  }

  private List<DraftChoicePicker.Choice> catalogue(
      Noise noise, String kind, List<DraftChoicePicker.Choice> builtIn) {
    String key = noise.dimension + ":" + kind;
    var values =
        builtIn.stream()
            .filter(
                choice ->
                    !kind.equals("processing")
                        || ProcessingExtensions.supports(choice.key(), noise.dimension))
            .map(
                choice ->
                    new DraftChoicePicker.Choice(
                        choice.key(),
                        kind.equals("algorithm")
                            ? algorithmLabel(noise.dimension, choice.key())
                            : state.metadata.label(kind + "." + choice.key(), choice.label()),
                        kind.equals("processing")
                            ? ProcessingRegistry.ITEMS.get(choice.key()).category()
                                + " / "
                                + ProcessingExtensions.resultType(choice.key(), noise.dimension)
                            : choice.detail()))
            .toList();
    var previous = catalogues.get(key);
    if (values.equals(previous)) return previous;
    catalogues.put(key, values);
    return values;
  }

  private void requestNoiseEdit(Noise noise, NoiseAuthoring.Request request) {
    if (noiseEdit != null) {
      authoringMessage = "Finish or cancel the pending noise edit first.";
      return;
    }
    var plan = NoiseAuthoring.plan(state.project(), noise, request);
    if (!plan.valid()) {
      if (Set.of(
              NoiseAuthoring.Action.DELETE,
              NoiseAuthoring.Action.DISABLE,
              NoiseAuthoring.Action.DELETE_PARAMETER)
          .contains(request.action())) {
        noiseEdit =
            new NoiseEditDialog(state, noise, request, selected -> afterNoiseEdit(noise, selected));
      } else
        authoringMessage =
            "! Edit rejected ("
                + plan.diagnostics().size()
                + " issue(s)): "
                + plan.diagnostics().stream().limit(4).toList();
      return;
    }
    plan.applyTo(noise);
    afterNoiseEdit(noise, plan.selected());
  }

  private void afterNoiseEdit(Noise noise, UUID selected) {
    noiseChanged();
    f.clear();
    authoringMessage = "";
    var row = index(noise).get(selected);
    if (row != null) noiseBrowser.select(row.section(), selected);
  }

  private void localChoice(
      Noise noise,
      String label,
      String mode,
      UUID owner,
      String type,
      UUID current,
      Consumer<UUID> change) {
    var choices = index(noise).choices(mode, owner, type);
    if (choices.isEmpty())
      ImGui.textDisabled(
          mode.equals("parameter")
              ? "No shared parameter with type " + type
              : "No enabled compatible " + type + " choices before this stage");
    draftChoices.draw(
        label,
        current == null ? null : current.toString(),
        choices,
        null,
        value -> {
          change.accept(UUID.fromString(value));
          noiseChanged();
        });
  }

  private void localLink(Noise noise, UUID uuid) {
    var row = index(noise).get(uuid);
    if (row != null && ImGui.smallButton("Go To Control")) focusLocal(noise, uuid, null);
  }

  private void focusLocal(Noise noise, UUID uuid, String path) {
    goToProblem(
        new EditorEvents.Target(state.project().project.uuid, noise.uuid, uuid, path, "", false));
  }

  private void dependencies(Noise noise, UUID uuid) {
    var users = index(noise).users(uuid);
    if (!ImGui.treeNode("Used By (" + users.size() + ")###uses")) return;
    ImGui.beginChild("local-users", 0, 120, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(users.size(), ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var use = users.get(i);
          if (ImGui.selectable(AppMetadata.uiText(use.label() + " / " + use.path()) + "###use" + i))
            focusLocal(noise, use.owner(), use.path());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ImGui.treePop();
  }

  private void pinnedControls(Noise noise) {
    pinCenters.clear();
    if (pinBrowser.synchronize(noise, noiseVersion)) pinFields.clear();
    if (pinFocusPin != null
        && (pinBrowser.selected() != pinFocusPin
            || !Objects.equals(pinFocusControl, pinFocusPin.targetUuid))) {
      propertyFocus.cancel();
      pinFocusPin = null;
      pinFocusKey = null;
      pinMessage = "The pin changed. Follow its current link again.";
    }
    boolean targeted =
        navigationDraft == state.draft && propertyFocus.matches(pinFocusControl, pinFocusKey);
    if (targeted) ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (noise.pinnedControls.isEmpty()) {
      if (targeted) {
        ImGui.textWrapped("No pinned controls remain in this draft.");
        propertyFocus.cancel();
        pinFocusKey = null;
      }
      return;
    }
    if (!ImGui.collapsingHeader("Pinned Controls")) return;
    draftChoices.draw(
        "Show group",
        pinBrowser.group(),
        pinBrowser.groups(),
        null,
        key -> {
          pinBrowser.showGroup(key);
          cancelPinFocus();
        });
    pinCenters.put("groups", draftChoices.buttonCenter());
    ImGui.text(pinBrowser.rows().size() + " visible / " + noise.pinnedControls.size() + " pins");
    propertyControl(
        null,
        "pins",
        () ->
            draftChoices.draw(
                "Pinned control",
                pinBrowser.visibleToken(),
                pinBrowser.rows(),
                null,
                key -> {
                  if (pinBrowser.choose(key)) pinFields.clear();
                  cancelPinFocus();
                }),
        draftChoices::focused);
    pinCenters.put("picker", draftChoices.buttonCenter());
    var pin = pinBrowser.selected();
    if (pin == null) return;
    if (pinBrowser.visibleToken() == null)
      ImGui.textWrapped(
          "The selected pin is outside this group filter. Its private edits are preserved.");
    if (!pinMessage.isEmpty() && navigationDraft == state.draft) ImGui.textWrapped(pinMessage);
    ImGui.pushID(pinBrowser.token());
    propertyControl(
        pin.targetUuid,
        "pin/label",
        () -> pinFields.text("Label", pin.label, value -> pin.label = value));
    pinRect("label");
    propertyControl(
        pin.targetUuid,
        "pin/group",
        () -> pinFields.text("Group", pin.group, value -> pin.group = value));
    pinRect("group");
    var row = pin.targetUuid == null ? null : index(noise).get(pin.targetUuid);
    Item item = null;
    if (row != null && row.section() != NoiseDraftIndex.Section.PARAMETERS) {
      var items =
          row.section() == NoiseDraftIndex.Section.TRANSFORMS
              ? noise.coordinateTransforms
              : noise.processing;
      item =
          items.stream()
              .filter(value -> Objects.equals(value.uuid, pin.targetUuid))
              .findFirst()
              .orElse(null);
    }
    if (row != null) {
      ImGui.textWrapped("Source: " + row.id() + " / " + row.type());
      if (row.section() == NoiseDraftIndex.Section.PARAMETERS) {
        var parameter =
            noise.sharedParameters.stream()
                .filter(value -> Objects.equals(value.uuid, pin.targetUuid))
                .findFirst()
                .orElseThrow();
        constantEditor(
            pinFields,
            parameter.type,
            parameter.value,
            value -> parameter.value = value,
            (key, draw) -> {
              draw.run();
              pinRect(key);
            });
      } else {
        ImGui.textWrapped(
            "This pin references the original item. Its input sources are shown below.");
        if (item != null) {
          int shown = 0;
          for (var input : item.inputs.entrySet()) {
            if (shown++ >= 8) break;
            ImGui.pushID(input.getKey());
            ImGui.textWrapped(
                input.getKey() + ": " + PinSources.input(noise, state.project(), input.getValue()));
            if (ImGui.smallButton("Go To Input"))
              focusLocal(noise, item.uuid, "inputs/" + input.getKey());
            pinRect("input/" + input.getKey());
            ImGui.popID();
          }
          if (item.inputs.size() > 8)
            ImGui.textWrapped("More inputs are available in the original control.");
        }
      }
      if (ImGui.smallButton("Go To Control"))
        focusLocal(noise, pin.targetUuid, PinSources.control(row, item));
      pinRect("source");
    } else
      ImGui.textWrapped(
          "The source control is missing. Unpin this reference or repair the original draft.");
    if (ImGui.smallButton("Move Pin Up") && pinBrowser.move(-1)) {
      noiseChanged();
      pinFields.clear();
    }
    pinRect("up");
    ImGui.sameLine();
    if (ImGui.smallButton("Move Pin Down") && pinBrowser.move(1)) {
      noiseChanged();
      pinFields.clear();
    }
    pinRect("down");
    ImGui.sameLine();
    if (ImGui.smallButton("Unpin") && pinBrowser.remove()) {
      noiseChanged();
      pinFields.clear();
    }
    pinRect("remove");
    ImGui.popID();
  }

  void probeOpenCurve() {
    openCurve(((Noise) state.draft).processing.getFirst(), null);
    curve.probeEnable();
  }

  CurveDialog probeCurveDialog() {
    return curve;
  }

  void probeCurvePanel() {
    if (curve != null && curve.draw()) curve = null;
  }

  private Item inputOwner;
  private long inputVersion = -1;
  private List<DraftChoicePicker.Choice> inputChoices = List.of();

  private final Map<String, float[]> expressionRects = new HashMap<>();
  private boolean expressionActive;
  private boolean expressionFocusSubmitted;
  private final ExpressionSelectionCallback expressionSelection = new ExpressionSelectionCallback();

  ExpressionSelection.Range probeExpressionSelection() {
    return expressionSelection.applied();
  }

  boolean probeExpressionActive() {
    return expressionActive;
  }

  private void expressionRect(String key) {
    expressionRects.put(
        key,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  float[] probeExpressionControl(String key) {
    return expressionRects.get(key);
  }

  EditorEvents.Target probeExpressionTarget() {
    return navigationTarget;
  }

  void probeExpressionPanel() {
    var noise = (Noise) state.draft;
    expressionControls(noise, noise.processing.getFirst());
    drawExpressionDialog();
  }

  private void drawExpressionDialog() {
    if (expressionDialog != null && expressionDialog.draw()) expressionDialog = null;
  }

  float[] probeExpandedExpressionControl(String key) {
    return expressionDialog == null ? null : expressionDialog.control(key);
  }

  String probeExpandedExpressionText() {
    return expressionDialog == null ? null : expressionDialog.bufferText();
  }

  ExpressionSelection.Range probeExpandedExpressionSelection() {
    return expressionDialog == null ? null : expressionDialog.selectedRange();
  }

  boolean probeExpandedExpressionOpen() {
    return expressionDialog != null;
  }

  void probeOpenExpandedExpression() {
    var noise = (Noise) state.draft;
    openExpandedExpression(noise, noise.processing.getFirst());
  }

  private void openExpandedExpression(Noise noise, Item item) {
    if (expressionDialog != null) {
      state.status = "Finish or cancel the expanded expression before opening another.";
      return;
    }
    expressionSelection.cancel();
    try {
      expressionDialog =
          new ExpressionEditDialog(
              state,
              new ExpressionEditSession(state, noise, item),
              () -> {
                try {
                  f.clear();
                } finally {
                  noiseChanged();
                }
              });
    } catch (RuntimeException invalid) {
      authoringMessage =
          "! " + Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
    }
  }

  private void expressionControls(Noise n, Item item) {
    resultType(item, List.of("Scalar", "Boolean"), v -> item.resultType = v);
    String expr =
        item.parameters.containsKey("expression") ? item.parameters.get("expression").asText() : "";
    boolean expressionTarget =
        navigationDraft == state.draft
            && searchScroll
            && navigationTarget != null
            && item.uuid.equals(navigationTarget.control())
            && Set.of("expression", "parameters/expression")
                .contains(Objects.requireNonNullElse(navigationTarget.parameter(), ""));
    expressionSelection.context(state.project(), state.draft, item, expr);
    if (expressionTarget && !expressionFocusSubmitted) {
      expressionSelection.request(expr, expressionFeedback.inspect(state.project(), n, item));
      ImGui.setKeyboardFocusHere();
      expressionFocusSubmitted = true;
    }
    f.text(
        "Expression",
        expr,
        v -> item.parameters.put("expression", ProjectIO.JSON.valueToTree(v)),
        expressionSelection);
    expressionRect("text");
    expressionActive = ImGui.isItemActive();
    if (expressionTarget) {
      ImGui.setScrollHereY(.25f);
      // ImGui applies focus on a later frame. Do not resubmit it while waiting:
      // repeated activation can reset a user's text selection.
      if (expressionActive) {
        searchScroll = false;
        expressionFocusSubmitted = false;
      }
    }
    if (ImGui.smallButton("Expand Expression")) {
      openExpandedExpression(n, item);
    }
    expressionRect("expand");
    var feedback = expressionFeedback.inspect(state.project(), n, item);
    if (!feedback.valid()) {
      ImGui.textWrapped("! " + feedback.error());
      if (feedback.input() != null) {
        if (ImGui.smallButton("Go To Input: " + feedback.input()))
          goToProblem(
              new EditorEvents.Target(
                  state.project().project.uuid,
                  n.uuid,
                  item.uuid,
                  "inputs/" + feedback.input(),
                  "",
                  false));
        expressionRect("input");
      } else {
        ImGui.textWrapped(
            "Expression characters "
                + feedback.start()
                + ".."
                + feedback.end()
                + " (zero-based, end exclusive)");
        if (ImGui.smallButton("Focus Expression")) {
          goToProblem(
              new EditorEvents.Target(
                  state.project().project.uuid, n.uuid, item.uuid, "expression", "", false));
        }
        expressionRect("focus");
        if (ImGui.isItemHovered())
          ImGui.setTooltip(
              "Select the current parser error, or place the caret at an empty error span.");
      }
    } else {
      ImGui.textWrapped(
          feedback.type()
              + " expression; "
              + (feedback.inputs().isEmpty()
                  ? "constant output"
                  : "inputs: " + String.join(", ", feedback.inputs())));
      if (smokeExpressionMode) {
        smokeExpressionDrawn = true;
        ImGui.setNextItemOpen(true, ImGuiCond.Always);
      }
      if (ImGui.treeNode("Evaluation details")) {
        var stats = feedback.statistics();
        ImGui.text(
            "Constant parts: "
                + stats.foldedNodes()
                + "; removed branches: "
                + stats.prunedBranches());
        ImGui.text(
            "Shared calculations: "
                + stats.sharedExpressions()
                + "; instructions: "
                + stats.instructions());
        ImGui.textWrapped("Input and resource references receive full validation on Apply.");
        ImGui.treePop();
      }
    }
  }

  private List<Map.Entry<String, Binding>> selectedInputs(Item item) {
    if (item.inputs.isEmpty()) return List.of();
    if (inputOwner != item || inputVersion != noiseVersion) {
      inputOwner = item;
      inputVersion = noiseVersion;
      inputChoices =
          item.inputs.entrySet().stream()
              .map(
                  entry ->
                      new DraftChoicePicker.Choice(
                          entry.getKey(),
                          entry.getKey(),
                          entry.getValue().type + " / " + entry.getValue().sourceKind))
              .toList();
    }
    var noise = (Noise) state.draft;
    String inputKey = inputSelection.current(noise, item);
    draftChoices.draw(
        "Input control",
        inputKey,
        inputChoices,
        null,
        value -> {
          inputSelection.select(noise, item, value);
          propertyFocus.cancel();
          expressionSelection.cancel();
          searchScroll = false;
          bindingFocusSubmitted = false;
          f.clear();
        });
    bindingRects.put(item.uuid + ":picker", draftChoices.buttonCenter());
    ImGui.setNextItemOpen(true, ImGuiCond.Once);
    String current = inputSelection.current(noise, item);
    return List.of(Map.entry(current, item.inputs.get(current)));
  }

  private void namedInput(Item item, String key) {
    String token = item.uuid + ":" + key;
    f.text("New input name", key, value -> inputNames.put(token, value));
    if (ImGui.smallButton("Rename Named Input")) {
      String value = inputNames.getOrDefault(token, key);
      if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")
          || !value.equals(key) && item.inputs.containsKey(value)) {
        authoringMessage = "! Input names must be distinct identifiers.";
      } else {
        var renamed = new LinkedHashMap<String, Binding>();
        item.inputs.forEach(
            (name, binding) -> renamed.put(name.equals(key) ? value : name, binding));
        item.inputs = renamed;
        inputNames.remove(token);
        noiseChanged();
        f.clear();
        authoringMessage = "Input renamed. Update its uses in Expression text before Apply.";
      }
    }
  }

  void probeOpenReference(UUID uuid) {
    openReference(uuid);
  }

  float[] probeReferenceButton(String id) {
    return referenceButtons.get(id).clone();
  }

  int probeItemRows() {
    return noiseBrowser.submitted();
  }

  int probeIndexBuilds() {
    return noiseIndex.builds();
  }

  private void referenceLinks(UUID uuid) {
    if (uuid == null) return;
    if (ImGui.button("Open Referenced Noise Generator")) openReference(uuid);
    ImGui.sameLine();
    if (ImGui.smallButton("Find References")) findReferences.accept(uuid);
    if (ImGui.smallButton("Select Resource in Tree")) openReference(uuid);
  }

  private void openReference(UUID uuid) {
    if (searchBlocked()) {
      authoringMessage = "Close the private edit before navigating.";
      return;
    }
    var target = new EditorEvents.Target(state.project().project.uuid, uuid, null, null, "", false);
    var result = referenceNavigation.request(target);
    if (result == SearchNavigation.Result.NEEDS_DECISION) referencePrompt = true;
    else if (result == SearchNavigation.Result.UNAVAILABLE)
      authoringMessage = "Referenced resource no longer exists.";
  }

  private void drawReferenceGuard() {
    if (referencePrompt) {
      ImGui.openPopup("Leave Noise Property Draft");
      referencePrompt = false;
    }
    if (!ImGui.beginPopupModal("Leave Noise Property Draft", ImGuiWindowFlags.AlwaysAutoResize))
      return;
    ImGui.textWrapped("Apply the current draft, discard it for this navigation, or cancel.");
    for (var decision : SearchNavigation.Decision.values()) {
      if (decision != SearchNavigation.Decision.APPLY) ImGui.sameLine();
      boolean clicked = ImGui.button(decision.name());
      var a = ImGui.getItemRectMin();
      var b = ImGui.getItemRectMax();
      referenceButtons.put(decision.name(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
      if (clicked) {
        var target = referenceNavigation.target();
        var result = referenceNavigation.decide(decision);
        if (result == SearchNavigation.Result.NAVIGATED && target != null) goToProblem(target);
        if (result != SearchNavigation.Result.NEEDS_DECISION) ImGui.closeCurrentPopup();
        else ImGui.textWrapped("Apply failed. Correct the draft or choose Discard/Cancel.");
      }
    }
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      referenceNavigation.decide(SearchNavigation.Decision.CANCEL);
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private boolean curveContains(Item item, UUID uuid) {
    if (uuid == null || !item.parameters.containsKey("curve")) return false;
    var points = item.parameters.get("curve").get("points");
    if (points == null) return false;
    for (var point : points)
      if (point.has("uuid") && uuid.toString().equals(point.get("uuid").asText())) return true;
    return false;
  }

  private String algorithmLabel(int dimension, String key) {
    var control = state.metadata.control("noise" + dimension + "D.algorithm");
    return control != null && control.options.containsKey(key)
        ? state.metadata.option("noise" + dimension + "D.algorithm", key)
        : state.metadata.label("algorithm." + key, key);
  }

  private void metadataTooltip(String id) {
    var control = state.metadata.control(id);
    if (control != null && ImGui.isItemHovered()) ImGui.setTooltip(control.tooltip);
  }

  private final Set<String> sourceControlKeys = new LinkedHashSet<>();
  private final Map<String, float[]> sourceControlCenters = new HashMap<>();

  Set<String> probeSourceControls() {
    return Set.copyOf(sourceControlKeys);
  }

  float[] probeSourceControl(String key) {
    return sourceControlCenters.get(key);
  }

  float[] probeSourceOption(String key) {
    return draftChoices.option(key);
  }

  private void algorithmParameter(Noise noise, NoiseAlgorithmRegistry.Parameter control) {
    String key = control.key(),
        id = "noise" + noise.dimension + "D.algorithm." + noise.algorithm.key + "." + key;
    sourceControlKeys.add(key);
    double current = noise.algorithm.parameters.getOrDefault(key, control.defaultValue());
    if (!control.choices().isEmpty()) {
      var options = new ArrayList<DraftChoicePicker.Choice>();
      for (int i = 0; i < control.choices().size(); i++)
        options.add(
            new DraftChoicePicker.Choice(
                Integer.toString(i),
                state.metadata.option(id, control.choices().get(i)),
                control.choices().get(i)));
      String cache = "source-choice:" + id;
      var previous = catalogues.get(cache);
      if (!options.equals(previous)) {
        previous = List.copyOf(options);
        catalogues.put(cache, previous);
      }
      var sourceOptions = previous;
      double choiceValue = current;
      propertyControl(
          null,
          "algorithm/parameters/" + key,
          () ->
              draftChoices.draw(
                  state.metadata.label(id, control.label()),
                  Double.isFinite(choiceValue) && choiceValue == Math.rint(choiceValue)
                      ? Integer.toString((int) choiceValue)
                      : Double.toString(choiceValue),
                  sourceOptions,
                  null,
                  value -> {
                    noise.algorithm.parameters.put(key, Double.parseDouble(value));
                    noiseChanged();
                  }),
          draftChoices::focused);
    } else {
      ImGui.pushID(id);
      double[] slider = {current};
      if (ImGui.sliderScalar(
          "##source-slider",
          slider,
          control.presetMinimum(),
          control.presetMaximum(),
          control.integer() ? "%.0f" : "%.5f")) {
        noise.algorithm.parameters.put(
            key, control.integer() ? StrictMath.rint(slider[0]) : slider[0]);
        noiseChanged();
        current = noise.algorithm.parameters.get(key);
      }
      double inputValue = current;
      propertyControl(
          null,
          "algorithm/parameters/" + key,
          () ->
              f.number(
                  state.metadata.label(id, control.label()),
                  inputValue,
                  value -> noise.algorithm.parameters.put(key, value)));
      ImGui.popID();
    }
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    sourceControlCenters.put(
        key,
        control.choices().isEmpty()
            ? new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2}
            : draftChoices.buttonCenter());
    f.tooltip(
        control.help() + " Engine range [" + control.minimum() + ", " + control.maximum() + "].");
    metadataTooltip(id);
    revealSearchField(null, "algorithm/parameters/" + key);
  }

  private final Map<String, float[]> parameterCenters = new HashMap<>();

  float[] probeParameter(UUID item, String key) {
    return parameterCenters.get(item + ":" + key);
  }

  List<DraftChoicePicker.Choice> probeOperations(Noise noise) {
    return catalogue(noise, "processing", OPERATIONS);
  }

  private void parameter(Item item, String key, double def) {
    if (ProcessingExtensions.REMAPPING.contains(item.kind)) {
      var control = CreationRegistry.get("processing." + item.kind + ".parameter." + key);
      double[] slider = {
        item.parameters.containsKey(key) ? item.parameters.get(key).asDouble() : def
      };
      if (ImGui.sliderScalar(
          "##slider-" + key,
          slider,
          control.minimum(),
          control.maximum(),
          control.kind() == CreationRegistry.Kind.INTEGER ? "%.0f" : "%.5f")) {
        item.parameters.put(
            key,
            ProjectIO.JSON.valueToTree(
                control.kind() == CreationRegistry.Kind.INTEGER
                    ? StrictMath.rint(slider[0])
                    : slider[0]));
        noiseChanged();
      }
    }
    propertyControl(
        item.uuid,
        "parameters/" + key,
        () ->
            f.number(
                state.metadata.label("processing." + item.kind + ".parameter." + key, key),
                item.parameters.containsKey(key) ? item.parameters.get(key).asDouble() : def,
                v -> item.parameters.put(key, ProjectIO.JSON.valueToTree(v))));
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    parameterCenters.put(
        item.uuid + ":" + key,
        new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
    metadataTooltip("processing." + item.kind + ".parameter." + key);
    revealSearchField(item.uuid, "parameters/" + key);
  }

  private void extensionControls(Noise noise, Item item) {
    var detail = ProcessingExtensions.DETAILS.get(item.kind);
    ImGui.textWrapped(detail.description());
    for (var control : detail.controls()) {
      boolean targeted =
          navigationTarget != null
              && item.uuid.equals(navigationTarget.control())
              && ("parameters/" + control.key())
                  .equals(DiagnosticNavigation.controlParameter(navigationTarget));
      if (!targeted && !ProcessingExtensions.visible(item, control, noise.dimension)) continue;
      String key = control.key();
      String id = "processing." + item.kind + ".parameter." + key;
      String label = state.metadata.label(id, control.label());
      if (control.defaultValue() instanceof Boolean defaultValue) {
        propertyControl(
            item.uuid,
            "parameters/" + key,
            () ->
                f.bool(
                    label,
                    item.parameters.containsKey(key)
                        ? item.parameters.get(key).asBoolean()
                        : defaultValue,
                    value -> item.parameters.put(key, ProjectIO.JSON.valueToTree(value))));
        metadataTooltip(id);
        revealSearchField(item.uuid, "parameters/" + key);
      } else if (control.options().isEmpty())
        parameter(item, key, ((Number) control.defaultValue()).doubleValue());
      else {
        List<String> options =
            noise.dimension == 2 && key.equals("axis") ? List.of("x", "z") : control.options();
        if (ProcessingExtensions.REMAPPING.contains(item.kind)) {
          var choices =
              options.stream()
                  .map(
                      value ->
                          new DraftChoicePicker.Choice(
                              value, state.metadata.option(id, value), value))
                  .toList();
          String cache = "manipulation-choice:" + id + ":" + noise.dimension;
          var previous = catalogues.get(cache);
          if (!choices.equals(previous)) {
            previous = choices;
            catalogues.put(cache, previous);
          }
          var extensionOptions = previous;
          propertyControl(
              item.uuid,
              "parameters/" + key,
              () ->
                  draftChoices.draw(
                      label,
                      item.parameters.containsKey(key)
                          ? item.parameters.get(key).asText()
                          : control.defaultValue().toString(),
                      extensionOptions,
                      null,
                      value -> {
                        item.parameters.put(key, ProjectIO.JSON.valueToTree(value));
                        noiseChanged();
                      }),
              draftChoices::focused);
          parameterCenters.put(item.uuid + ":" + key, draftChoices.buttonCenter());
        } else
          propertyControl(
              item.uuid,
              "parameters/" + key,
              () ->
                  f.choice(
                      label,
                      item.parameters.containsKey(key)
                          ? item.parameters.get(key).asText()
                          : control.defaultValue().toString(),
                      options,
                      value -> item.parameters.put(key, ProjectIO.JSON.valueToTree(value))));
        metadataTooltip(id);
        revealSearchField(item.uuid, "parameters/" + key);
      }
    }
    if (ProcessingExtensions.REMAPPING.contains(item.kind)) remappingPreview(noise, item);
    if (item.kind.equals("BLEND"))
      f.bool(
          "Field-driven weight",
          item.inputs.containsKey("weight"),
          value -> {
            if (value) item.inputs.put("weight", Binding.constant(.5));
            else item.inputs.remove("weight");
          });
    if (ProcessingExtensions.SHAPES.contains(item.kind) || item.kind.equals("FALLOFF")) {
      f.bool(
          item.kind.equals("FALLOFF") ? "Explicit falloff position" : "Explicit shape position",
          item.inputs.containsKey("position"),
          value -> {
            if (!value) item.inputs.remove("position");
            else {
              var binding = Binding.constant(0);
              binding.type = noise.dimension == 2 ? "Coordinates2D" : "Coordinates3D";
              binding.value = NoiseAuthoring.defaultValue(binding.type);
              item.inputs.put("position", binding);
            }
          });
      if (!item.kind.equals("FALLOFF"))
        ImGui.textWrapped(
            "Negative distance is inside; zero is on the surface. Default position uses transformed"
                + " definition coordinates.");
    }
  }

  private void remappingPreview(Noise noise, Item item) {
    if (item.kind.equals("BLEND")) return;
    try {
      var plan = ScalarRemapping.compile(item, noise.dimension);
      float[] values = new float[65];
      for (int i = 0; i < values.length; i++) {
        double u = -1 + 3.0 * i / (values.length - 1);
        double coordinate =
            item.kind.equals("FALLOFF")
                ? u
                    * item.parameters
                        .getOrDefault("extent", ProjectIO.JSON.valueToTree(64))
                        .doubleValue()
                : 0;
        double value = plan.sample(item.kind.equals("FALLOFF") ? 1 : u, coordinate, 0, 0);
        if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
          ImGui.textWrapped(
              "Preview exceeds display range; canonical evaluation reports numeric failures.");
          return;
        }
        values[i] = (float) value;
      }
      ImGui.plotLines("Transfer preview###remapping-preview", values, values.length);
      ImGui.textWrapped(
          item.kind.equals("FALLOFF")
              ? "Unit input; X position from -extent to 2*extent, Y/Z=0. Authored centers and"
                  + " domain apply."
              : "Input from -1 to 2; raw output. Display only.");
    } catch (RuntimeException invalid) {
      ImGui.textWrapped("Preview unavailable: " + invalid.getMessage());
    }
  }

  private String uniqueItem(Noise n, String base) {
    var names = new ArrayList<String>();
    n.processing.forEach(i -> names.add(i.id));
    n.coordinateTransforms.forEach(i -> names.add(i.id));
    return ResourceTransactions.unique(base, names);
  }

  private void itemActions(Noise noise, List<Item> list, int i) {
    UUID uuid = list.get(i).uuid;
    if (ImGui.smallButton("Up") && i > 0)
      requestNoiseEdit(noise, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.UP, uuid));
    ImGui.sameLine();
    if (ImGui.smallButton("Down") && i < list.size() - 1)
      requestNoiseEdit(noise, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.DOWN, uuid));
    ImGui.sameLine();
    if (ImGui.smallButton("Duplicate"))
      requestNoiseEdit(noise, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.DUPLICATE, uuid));
    ImGui.sameLine();
    if (ImGui.smallButton("Delete..."))
      requestNoiseEdit(noise, NoiseAuthoring.Request.simple(NoiseAuthoring.Action.DELETE, uuid));
    ImGui.sameLine();
    if (ImGui.smallButton("Pin Item")) {
      if (noise.pinnedControls.stream().noneMatch(pin -> pin.targetUuid.equals(uuid))) {
        var pin = new Pin();
        pin.targetUuid = uuid;
        pin.label = list.get(i).id;
        noise.pinnedControls.add(pin);
        noiseChanged();
      }
    }
    dependencies(noise, uuid);
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

  private final Map<UUID, Set<String>> warpControlKeys = new HashMap<>();

  Set<String> probeWarpControls(UUID item) {
    return Set.copyOf(warpControlKeys.getOrDefault(item, Set.of()));
  }

  private void warpControls(Noise noise, Item item, int index, boolean coordinateStage) {
    var submitted = new LinkedHashSet<String>();
    warpControlKeys.put(item.uuid, submitted);
    ImGui.textWrapped(WarpRegistry.SEMANTICS);
    ImGui.textWrapped(
        "Algorithm seed: mix(generator seed + raw IEEE bits of seedOffset). External fields keep"
            + " their own seed. Numeric components remain stage values; sampled components"
            + " reevaluate at the warp query location.");
    String prefix =
        coordinateStage
            ? "transform" + noise.dimension + "D.DOMAIN_WARP."
            : "processing.WARP_SAMPLE.parameter.";
    var visible = new HashSet<String>();
    for (var c : WarpRegistry.visibleControls(item, noise.dimension)) visible.add(c.key());
    var controls = new ArrayList<>(WarpRegistry.controls(noise.dimension));
    if (navigationTarget != null && item.uuid.equals(navigationTarget.control())) {
      String target =
          Objects.requireNonNullElse(DiagnosticNavigation.controlParameter(navigationTarget), "");
      for (var control : WarpRegistry.controls(3))
        if (target.equals("parameters/" + control.key())
            && controls.stream().noneMatch(c -> c.key().equals(control.key())))
          controls.add(control);
    }
    for (var control : controls) {
      String key = control.key();
      boolean targeted =
          navigationTarget != null
              && item.uuid.equals(navigationTarget.control())
              && ("parameters/" + key)
                  .equals(DiagnosticNavigation.controlParameter(navigationTarget));
      if (!targeted && !visible.contains(key)
          || coordinateStage && key.equals("amplitude") && item.inputs.containsKey("amplitude"))
        continue;
      submitted.add(key);
      ImGui.pushID(key);
      String id = prefix + key, label = state.metadata.label(id, control.label());
      var current =
          item.parameters.getOrDefault(key, ProjectIO.JSON.valueToTree(control.defaultValue()));
      if (!control.options().isEmpty()) {
        var choices = new ArrayList<DraftChoicePicker.Choice>();
        for (int option = 0; option < control.options().size(); option++) {
          String name = control.options().get(option);
          choices.add(
              new DraftChoicePicker.Choice(
                  control.numericOptions() ? Integer.toString(option) : name,
                  state.metadata.option(id, name),
                  name));
        }
        String cache = "warp:" + id;
        var previous = catalogues.get(cache);
        if (!choices.equals(previous)) {
          previous = List.copyOf(choices);
          catalogues.put(cache, previous);
        }
        String selected =
            control.numericOptions()
                ? current.isNumber() && current.doubleValue() == Math.rint(current.doubleValue())
                    ? Integer.toString(current.intValue())
                    : current.toString()
                : current.asText();
        var warpOptions = previous;
        propertyControl(
            item.uuid,
            "parameters/" + key,
            () ->
                draftChoices.draw(
                    label,
                    selected,
                    warpOptions,
                    null,
                    value -> {
                      item.parameters.put(
                          key,
                          control.numericOptions()
                              ? ProjectIO.JSON.valueToTree(Integer.parseInt(value))
                              : ProjectIO.JSON.valueToTree(value));
                      noiseChanged();
                    }),
            draftChoices::focused);
        parameterCenters.put(item.uuid + ":" + key, draftChoices.buttonCenter());
      } else {
        double[] slider = {current.asDouble()};
        if (ImGui.sliderScalar(
            "##warp-slider",
            slider,
            control.presetMinimum(),
            control.presetMaximum(),
            control.integer() ? "%.0f" : "%.5f")) {
          item.parameters.put(
              key,
              ProjectIO.JSON.valueToTree(
                  control.integer() ? StrictMath.rint(slider[0]) : slider[0]));
          noiseChanged();
        }
        propertyControl(
            item.uuid,
            "parameters/" + key,
            () ->
                f.number(
                    label,
                    item.parameters
                        .getOrDefault(key, ProjectIO.JSON.valueToTree(control.defaultValue()))
                        .asDouble(),
                    value -> item.parameters.put(key, ProjectIO.JSON.valueToTree(value))));
        var lo = ImGui.getItemRectMin();
        var hi = ImGui.getItemRectMax();
        parameterCenters.put(
            item.uuid + ":" + key,
            new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
      }
      metadataTooltip(id);
      revealSearchField(item.uuid, "parameters/" + key);
      ImGui.popID();
    }
    for (int axis : noise.dimension == 2 ? new int[] {0, 2} : new int[] {0, 1, 2}) {
      String fieldKey = WarpRegistry.COMPONENTS.get(axis),
          numericKey = "warp" + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z");
      ImGui.pushID(fieldKey);
      String current =
          item.inputs.containsKey(fieldKey)
              ? item.inputs.containsKey(numericKey) ? "CONFLICT" : "SAMPLED_FIELD"
              : item.inputs.containsKey(numericKey) ? "STAGE_VALUE" : "ALGORITHM";
      f.choice(
          "Displacement " + numericKey.substring(4),
          current,
          List.of("ALGORITHM", "STAGE_VALUE", "SAMPLED_FIELD"),
          value -> {
            if (!value.equals("SAMPLED_FIELD")) item.inputs.remove(fieldKey);
            if (!value.equals("STAGE_VALUE")) item.inputs.remove(numericKey);
            if (value.equals("SAMPLED_FIELD"))
              item.inputs.putIfAbsent(
                  fieldKey, coordinateStage ? Binding.noiseField(null) : Binding.sourceField());
            if (value.equals("STAGE_VALUE"))
              item.inputs.putIfAbsent(numericKey, Binding.constant(0));
          });
      if (!item.inputs.containsKey(fieldKey) && !item.inputs.containsKey(numericKey))
        revealSearchField(item.uuid, "inputs/" + fieldKey);
      if (item.inputs.containsKey(fieldKey)) {
        if (coordinateStage) {
          fieldBinding(noise, item, item.inputs.get(fieldKey), true);
        } else ImGui.textWrapped("Configure this sampled component in its Input row above.");
      }
      if (item.inputs.containsKey(numericKey))
        binding(noise, item, index, item.inputs.get(numericKey));
      ImGui.popID();
    }
  }

  private void fieldBinding(Noise n, int index, Binding b) {
    fieldBinding(n, n.processing.get(index), b, false);
  }

  private void fieldBinding(Noise n, Item item, Binding b, boolean coordinateStage) {
    bindingField(
        item,
        b,
        "type",
        () -> f.choice("Type", b.type, List.of("Scalar"), value -> b.type = value));
    bindingField(
        item,
        b,
        "sourceKind",
        () ->
            f.choice(
                "Sampled field",
                b.sourceKind,
                coordinateStage
                    ? List.of("Noise Generator Field")
                    : List.of("Source Field", "Earlier Field Prefix", "Noise Generator Field"),
                v -> {
                  NoiseAuthoring.source(b, v);
                  b.type = "Scalar";
                }));
    if (b.sourceKind.equals("Earlier Field Prefix")) {
      bindingField(
          item,
          b,
          "itemUuid",
          () ->
              localChoice(
                  n,
                  "Prefix ends at",
                  "prefix",
                  item.uuid,
                  "Scalar",
                  b.itemUuid,
                  value -> b.itemUuid = value));
      localLink(n, b.itemUuid);
      ImGui.textWrapped(
          "Reevaluates this earlier result and its dependencies at each requested location.");
    } else if (b.sourceKind.equals("Noise Generator Field")) {
      bindingField(
          item,
          b,
          "noiseGeneratorUuid",
          () -> noisePicker("Field resource", b.noiseGeneratorUuid, u -> b.noiseGeneratorUuid = u));
      bindingField(
          item,
          b,
          "projection",
          () ->
              f.choice(
                  "Projection", b.projection, List.of("XZ", "XY", "YZ"), v -> b.projection = v));
      bindingField(
          item, b, "slice", () -> f.number("Inserted slice coordinate", b.slice, v -> b.slice = v));
      referenceLinks(b.noiseGeneratorUuid);
    }
  }

  private boolean inputNavigation(Item item, String name) {
    return searchScroll
        && navigationDraft == state.draft
        && navigationTarget != null
        && Objects.equals(navigationTarget.project(), state.project().project.uuid)
        && Objects.equals(navigationTarget.resource(), state.selected)
        && DiagnosticNavigation.inputTarget(navigationTarget, item.uuid, name);
  }

  private final Map<String, float[]> bindingRects = new HashMap<>();
  private String focusedBinding;

  private void bindingField(Item item, Binding binding, String field, Runnable draw) {
    String name =
        item.inputs.entrySet().stream()
            .filter(e -> e.getValue() == binding)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    boolean targeted =
        name != null
            && inputNavigation(item, name)
            && field.equals(DiagnosticNavigation.bindingField(navigationTarget, name, binding));
    if (targeted && !bindingFocusSubmitted) {
      ImGui.setKeyboardFocusHere();
      bindingFocusSubmitted = true;
    }
    draw.run();
    String key = item.uuid + ":" + name + ":" + field;
    bindingRects.put(
        key,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
    boolean acknowledged =
        switch (field) {
          case "parameterUuid", "itemUuid" -> draftChoices.focused();
          case "noiseGeneratorUuid" -> resourcePicker.focused();
          default -> ImGui.isItemActive() || ImGui.isItemFocused();
        };
    if (targeted) {
      ImGui.setScrollHereY(.25f);
      if (acknowledged) {
        focusedBinding = key;
        searchScroll = false;
        bindingFocusSubmitted = false;
      }
    }
  }

  float[] probeBindingControl(UUID item, String name, String field) {
    return bindingRects.get(item + ":" + name + ":" + field);
  }

  float[] probeInputPicker(UUID item) {
    return bindingRects.get(item + ":picker");
  }

  float[] probeInputOption(String name) {
    return draftChoices.option(name);
  }

  String probeInputSelection(Item item) {
    return inputSelection.current((Noise) state.draft, item);
  }

  String probeFocusedBinding() {
    return focusedBinding;
  }

  private void binding(Noise n, Item item, int index, Binding b) {
    bindingField(
        item,
        b,
        "type",
        () -> f.choice("Type", b.type, NoiseAuthoring.TYPES, v -> NoiseAuthoring.type(b, v)));
    boolean coordinateStage = n.coordinateTransforms.contains(item);
    bindingField(
        item,
        b,
        "sourceKind",
        () ->
            f.choice(
                "Source",
                b.sourceKind,
                NoiseDraftIndex.sources(b.type, coordinateStage),
                v -> NoiseAuthoring.source(b, v)));
    switch (b.sourceKind) {
      case "Constant" ->
          constantEditor(
              b.type, b.value, v -> b.value = v, (key, draw) -> bindingField(item, b, key, draw));
      case "Shared Parameter" -> {
        bindingField(
            item,
            b,
            "parameterUuid",
            () ->
                localChoice(
                    n,
                    "Parameter",
                    "parameter",
                    item.uuid,
                    b.type,
                    b.parameterUuid,
                    value -> b.parameterUuid = value));
        localLink(n, b.parameterUuid);
      }
      case "Ambient Value" ->
          bindingField(
              item,
              b,
              "ambientKey",
              () ->
                  f.choice(
                      "Ambient",
                      b.ambientKey == null ? "" : b.ambientKey,
                      new ArrayList<>(NoiseCompiler.AMBIENT),
                      v -> b.ambientKey = v));
      case "Earlier Result" -> {
        bindingField(
            item,
            b,
            "itemUuid",
            () ->
                localChoice(
                    n,
                    "Result",
                    "earlier",
                    item.uuid,
                    b.type,
                    b.itemUuid,
                    value -> b.itemUuid = value));
        localLink(n, b.itemUuid);
      }
      case "Source Result" -> {
        if (ImGui.smallButton("Go To Source Control")) focusLocal(n, null, "algorithm");
      }
      case "Noise Generator Reference" -> {
        bindingField(
            item,
            b,
            "noiseGeneratorUuid",
            () -> noisePicker("Resource", b.noiseGeneratorUuid, u -> b.noiseGeneratorUuid = u));
        bindingField(
            item,
            b,
            "projection",
            () ->
                f.choice(
                    "Projection", b.projection, List.of("XZ", "XY", "YZ"), v -> b.projection = v));
        bindingField(
            item, b, "slice", () -> f.number("Slice coordinate", b.slice, v -> b.slice = v));
        referenceLinks(b.noiseGeneratorUuid);
      }
      default -> {}
    }
  }

  private void constantEditor(String type, JsonNode value, Consumer<JsonNode> set) {
    constantEditor(type, value, set, (key, draw) -> draw.run());
  }

  private void constantEditor(
      String type, JsonNode value, Consumer<JsonNode> set, BiConsumer<String, Runnable> field) {
    constantEditor(f, type, value, set, field);
  }

  private void constantEditor(
      UiFields widgets,
      String type,
      JsonNode value,
      Consumer<JsonNode> set,
      BiConsumer<String, Runnable> field) {
    if (type.equals("Boolean"))
      field.accept(
          "value",
          () ->
              widgets.bool(
                  "Value",
                  value != null && value.asBoolean(),
                  v -> set.accept(ProjectIO.JSON.valueToTree(v))));
    else if (type.equals("Scalar"))
      field.accept(
          "value",
          () ->
              widgets.number(
                  "Value",
                  value == null ? 0 : value.asDouble(),
                  v -> set.accept(ProjectIO.JSON.valueToTree(v))));
    else {
      int count = type.endsWith("2") || type.endsWith("2D") ? 2 : 3;
      double[] vec = new double[count];
      for (int i = 0; i < count; i++)
        vec[i] = value != null && value.isArray() && value.size() > i ? value.get(i).asDouble() : 0;
      for (int i = 0; i < count; i++) {
        final int k = i;
        field.accept(
            "value/" + i,
            () ->
                widgets.number(
                    count == 2 ? (k == 0 ? "X" : "Z") : List.of("X", "Y", "Z").get(k),
                    vec[k],
                    v -> {
                      vec[k] = v;
                      set.accept(ProjectIO.JSON.valueToTree(vec));
                    }));
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
    resourcePicker.draw(
        label,
        "noise",
        current,
        "None",
        value -> {
          set.accept(value);
          noiseChanged();
        });
  }

  private void fieldPicker(String label, UUID current, Consumer<UUID> set) {
    resourcePicker.draw(label, "noise", current, "Placement field", set);
  }

  private void placement(Operation op) {
    worldControl("enabled", () -> f.bool("Enabled", op.enabled, v -> op.enabled = v));
    worldControl(
        "noiseGeneratorUuid",
        () -> noisePicker("Noise generator", op.noiseGeneratorUuid, v -> op.noiseGeneratorUuid = v),
        resourcePicker::focused);
    worldControl(
        "interpretation",
        () ->
            f.choice(
                "Interpretation",
                op.interpretation,
                WorldSemantics.INTERPRETATIONS,
                v -> op.interpretation = v));
    Layer layer = EditorState.layerFor(state.project(), op.uuid);
    if (layer == null) return;
    ImGui.text("Layer: " + layer.id);
    if (ImGui.button("Go To Source") && op.noiseGeneratorUuid != null)
      openReference(op.noiseGeneratorUuid);
    ImGui.separator();
    for (String key : SettingsResolver.PROPERTIES) {
      ImGui.pushID(key);
      revealSetting(key);
      boolean overridden = op.overrides.containsKey(key);
      worldControl(
          "override/" + key,
          () ->
              f.bool(
                  "Override " + AppMetadata.settingLabel(key),
                  overridden,
                  v -> {
                    if (v) {
                      JsonNode node = ProjectIO.JSON.valueToTree(layer.defaults);
                      op.overrides.put(key, node.get(key).deepCopy());
                    } else {
                      seedPrefix("settings/" + key, false);
                      op.overrides.remove(key);
                    }
                  }));
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
        if (ImGui.smallButton("Reset to Layer Default")) {
          seedPrefix("settings/" + key, false);
          op.overrides.remove(key);
        }
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

  private void revealSetting(String key) {
    if (worldRoute != null
        && navigationDraft == state.draft
        && Objects.equals(worldRoute.property(), key)) {
      if (searchScroll) {
        ImGui.setScrollHereY(.25f);
        searchScroll = false;
      }
      ThemeStyle.text("Information", "Source control: " + AppMetadata.settingLabel(key));
      if (!worldRoute.explanation().isEmpty()) ImGui.textWrapped(worldRoute.explanation());
    }
  }

  private void settings(Settings s) {
    for (String key : SettingsResolver.PROPERTIES) {
      ImGui.pushID(key);
      editSetting(s, key);
      ImGui.popID();
    }
  }

  private void editSetting(Settings s, String key) {
    String previous = worldSettingPath;
    worldSettingPath = key;
    try {
      editSettingValue(s, key);
    } finally {
      worldSettingPath = previous;
    }
  }

  private void editSettingValue(Settings s, String key) {
    revealSetting(key);
    try {
      var field = Settings.class.getField(key);
      if (field.getType() == double.class) {
        double value = field.getDouble(s);
        settingControl(
            "",
            () ->
                f.number(
                    AppMetadata.settingLabel(key),
                    value,
                    v -> {
                      try {
                        field.setDouble(s, v);
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    }));
      } else if (field.getType() == int.class) {
        int value = field.getInt(s);
        settingControl(
            "",
            () ->
                f.integer(
                    AppMetadata.settingLabel(key),
                    value,
                    v -> {
                      try {
                        field.setInt(s, v);
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    }));
      } else if (key.equals("seedOffset"))
        settingControl(
            "", () -> longText(AppMetadata.settingLabel(key), s.seedOffset, v -> s.seedOffset = v));
      else if (key.equals("action"))
        settingControl(
            "",
            () ->
                f.choice(
                    AppMetadata.settingLabel(key),
                    s.action,
                    List.of("NO_CHANGE", "SET", "CLEAR"),
                    v -> s.action = v));
      else if (key.equals("rounding"))
        settingControl(
            "",
            () ->
                f.choice(
                    AppMetadata.settingLabel(key),
                    s.rounding,
                    List.of("FLOOR", "CEIL", "ROUND"),
                    v -> s.rounding = v));
      else if (key.startsWith("axis")) {
        String value = (String) field.get(s);
        settingControl(
            "",
            () ->
                f.choice(
                    AppMetadata.settingLabel(key),
                    value,
                    WorldSemantics.AXES,
                    v -> {
                      try {
                        field.set(s, v);
                      } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                      }
                    }));
      } else if (key.equals("typeSource"))
        settingControl(
            "",
            () ->
                f.choice(
                    "Type Source",
                    s.typeSource,
                    WorldSemantics.TYPE_SOURCES,
                    v -> s.typeSource = v));
      else if (key.equals("samplingTransforms")) {
        if (settingTree("Ordered Sampling Transforms")) {
          samplingTransforms(s.samplingTransforms);
          ImGui.treePop();
        }
      } else if (key.equals("parameterDrivers")) {
        if (settingTree("Numeric Parameter Drivers")) {
          parameterDrivers(s);
          ImGui.treePop();
        }
      } else if (key.equals("typeIntervals")) {
        if (settingTree("Voxel Type Intervals")) {
          typeIntervals(s.typeIntervals);
          ImGui.treePop();
        }
      } else if (key.equals("probabilityDriver") || key.equals("strengthDriver")) {
        if (settingTree(AppMetadata.settingLabel(key))) {
          fieldDriver(key.equals("probabilityDriver") ? s.probabilityDriver : s.strengthDriver);
          ImGui.treePop();
        }
      } else if (key.equals("voxelTypeUuid")) {
        if (s.typeSource.equals("INTERVALS")) ImGui.textDisabled("Voxel targets: interval list");
        else
          settingControl(
              "",
              () ->
                  typePicker(
                      AppMetadata.settingLabel(key), s.voxelTypeUuid, v -> s.voxelTypeUuid = v),
              resourcePicker::focused);
      } else if (key.equals("voxelStatePredicate")) {
        if (settingTree("Previous Voxel State")) {
          predicate(s.voxelStatePredicate, 0);
          ImGui.treePop();
        }
      } else if (key.equals("fieldGates")) {
        if (settingTree("Field Selection Gates")) {
          gate(s.fieldGates, 0);
          ImGui.treePop();
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private void typePicker(String label, UUID uuid, Consumer<UUID> set) {
    resourcePicker.draw(label, "voxel", uuid, "None", set);
  }

  private void predicate(Predicate p, int depth) {
    if (depth > 16) {
      ImGui.text("Maximum editor nesting reached");
      return;
    }
    settingControl(
        "kind",
        () ->
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
                v -> p.kind = v));
    if (p.kind.startsWith("TYPE_"))
      settingControl(
          "reference",
          () -> typePicker("Voxel type", p.reference, v -> p.reference = v),
          resourcePicker::focused);
    else if (p.kind.equals("IN_SET") || p.kind.equals("NOT_IN_SET")) {
      settingControl(
          "reference",
          () -> resourcePicker.draw("Voxel set", "set", p.reference, null, v -> p.reference = v),
          resourcePicker::focused);
    }
    if (p.kind.equals("AND") || p.kind.equals("OR")) {
      for (int i = 0; i < p.children.size(); i++) {
        ImGui.pushID(i);
        if (ImGui.treeNode("Clause " + (i + 1))) {
          var child = p.children.get(i);
          nestedSetting("children", () -> predicate(child, depth + 1));
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
    settingControl(
        "kind",
        () ->
            f.choice(
                "Gate",
                g.kind,
                WorldSemantics.GATES,
                v -> {
                  g.kind = v;
                  if (v.equals("NOT") && g.children.isEmpty()) {
                    changedGateSeeds();
                    g.children.add(new Gate());
                  }
                }));
    if (g.kind.equals("COMPARE") || g.kind.equals("INTERVAL")) {
      settingControl(
          "noiseGeneratorUuid",
          () -> fieldPicker("Field", g.noiseGeneratorUuid, v -> g.noiseGeneratorUuid = v),
          resourcePicker::focused);
      if (g.kind.equals("COMPARE"))
        settingControl(
            "comparison",
            () ->
                f.choice(
                    "Comparison",
                    g.comparison,
                    List.of("GE", "GT", "LE", "LT", "EQ", "NE"),
                    v -> g.comparison = v));
      settingControl(
          "threshold", () -> f.number("Threshold / minimum", g.threshold, v -> g.threshold = v));
      if (g.kind.equals("INTERVAL")) {
        settingControl("max", () -> f.number("Maximum", g.max, v -> g.max = v));
        settingControl(
            "includeMin", () -> f.bool("Include minimum", g.includeMin, v -> g.includeMin = v));
        settingControl(
            "includeMax", () -> f.bool("Include maximum", g.includeMax, v -> g.includeMax = v));
      }
    }
    if (g.kind.equals("PROBABILITY")) {
      settingControl(
          "probability",
          () -> f.number("Gate probability [0,1]", g.probability, v -> g.probability = v));
      settingControl(
          "seedOffset", () -> longText("Gate seed offset", g.seedOffset, v -> g.seedOffset = v));
      ImGui.textWrapped("Independent deterministic XYZ draw, stable within this gate path.");
    }
    if (g.kind.equals("AND") || g.kind.equals("OR") || g.kind.equals("NOT")) {
      for (int i = 0; i < g.children.size(); i++) {
        ImGui.pushID(i);
        if (smokeWorldSettings != null) ImGui.setNextItemOpen(true, ImGuiCond.Always);
        if (ImGui.treeNode("Gate clause " + i)) {
          var child = g.children.get(i);
          nestedSetting("children/" + i, () -> gate(child, depth + 1));
          if (ImGui.smallButton("Remove")) {
            changedGateSeeds();
            g.children.remove(i);
          }
          ImGui.treePop();
        }
        ImGui.popID();
      }
      if ((!g.kind.equals("NOT") || g.children.isEmpty()) && ImGui.button("Add Gate")) {
        changedGateSeeds();
        g.children.add(new Gate());
      }
    }
  }

  private void fieldDriver(FieldDriver d) {
    fieldDriver(d, true);
  }

  private int parameterDriverRow = -1;
  private boolean probingParameterDrivers;
  private final Map<String, float[]> parameterDriverCenters = new HashMap<>();

  float[] probeParameterDriver(int row, String key) {
    return parameterDriverCenters.get(row + ":" + key);
  }

  void probeParameterDriverPanel() {
    probingParameterDrivers = true;
    try {
      parameterDrivers(((Layer) state.draft).defaults);
    } finally {
      probingParameterDrivers = false;
    }
  }

  private void driverNumber(String key, String label, double value, DoubleConsumer update) {
    settingControl(key, () -> f.number(label, value, update));
    if (parameterDriverRow >= 0) {
      var lo = ImGui.getItemRectMin();
      var hi = ImGui.getItemRectMax();
      parameterDriverCenters.put(
          parameterDriverRow + ":" + key,
          new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
    }
  }

  private void fieldDriver(FieldDriver d, boolean unitOutput) {
    settingControl("enabled", () -> f.bool("Enabled", d.enabled, v -> d.enabled = v));
    settingControl(
        "noiseGeneratorUuid",
        () -> fieldPicker("Field", d.noiseGeneratorUuid, v -> d.noiseGeneratorUuid = v),
        resourcePicker::focused);
    settingControl(
        "mapping",
        () -> f.choice("Mapping", d.mapping, WorldSemantics.MAPPINGS, v -> d.mapping = v));
    driverNumber("inputMin", "Input minimum", d.inputMin, v -> d.inputMin = v);
    driverNumber("inputMax", "Input maximum", d.inputMax, v -> d.inputMax = v);
    driverNumber(
        "outputMin",
        "Output at minimum" + (unitOutput ? " [0,1]" : ""),
        d.outputMin,
        v -> d.outputMin = v);
    driverNumber(
        "outputMax",
        "Output at maximum" + (unitOutput ? " [0,1]" : ""),
        d.outputMax,
        v -> d.outputMax = v);
    if (unitOutput)
      ImGui.textWrapped(
          "Samples use the placement coordinates. Input clamps to the declared range; the mapped"
              + " factor multiplies the constant. Disabled factor = 1. Use ALL interpretation for a"
              + " parameter-only input.");
  }

  private void parameterDrivers(Settings settings) {
    var drivers = settings.parameterDrivers;
    ImGui.textWrapped(
        "Entries run in stored order before interpretation. Replace, add to, or multiply the"
            + " current numeric value. Input clamps to its range; finite signed output endpoints"
            + " are allowed.");
    ImGui.textWrapped(
        "Sampling scale/offset and transform-component rows run first and require an explicit"
            + " field, sampled at baseline placement coordinates. Interpretation rows run next;"
            + " strength/probability rows run only after interpretation and type selection pass."
            + " Each phase preserves authored row order.");
    int remove = -1, up = -1, down = -1;
    for (int i = 0; i < drivers.size(); i++) {
      ImGui.pushID(i);
      var d = drivers.get(i);
      if (smokeWorldSettings != null || probingParameterDrivers)
        ImGui.setNextItemOpen(true, ImGuiCond.Always);
      if (ImGui.treeNode("Driver " + (i + 1) + " : " + AppMetadata.settingLabel(d.destination))) {
        if (ImGui.beginCombo("Destination", AppMetadata.settingLabel(d.destination))) {
          for (String destination : WorldSemantics.PARAMETER_TARGETS)
            if (ImGui.selectable(
                AppMetadata.settingLabel(destination), d.destination.equals(destination))) {
              d.destination = destination;
              d.transformIndex =
                  WorldSemantics.parameter(destination).transform()
                      ? Math.max(0, d.transformIndex)
                      : -1;
            }
          ImGui.endCombo();
        }
        f.choice("Combination", d.combination, WorldSemantics.COMBINATIONS, v -> d.combination = v);
        if (WorldSemantics.PARAMETER_TARGETS.contains(d.destination)) {
          var target = WorldSemantics.parameter(d.destination);
          if (target.transform()) {
            f.integer(
                "Transform row (1-based)",
                d.transformIndex + 1,
                v -> d.transformIndex = v <= 0 ? -1 : v - 1);
            if (d.transformIndex >= 0 && d.transformIndex < settings.samplingTransforms.size()) {
              var transform = settings.samplingTransforms.get(d.transformIndex);
              ImGui.textWrapped(
                  transform.kind + (transform.enabled ? "" : " (disabled; driver is skipped)"));
            }
            ImGui.textWrapped(
                "Row numbers follow the ordered transform list. Reordering transforms changes the"
                    + " target. Rotation components use degrees; pivots apply to Scale and"
                    + " Rotate.");
          }
          ImGui.textWrapped(
              "Phase: "
                  + target.phase
                  + (target.explicitField
                      ? ". Explicit field required; baseline coordinates are fixed for all sampling"
                          + " rows."
                      : target.unitRange ? ". Every effective result must remain in [0,1]." : "."));
        }
        parameterDriverRow = i;
        try {
          fieldDriver(d, false);
        } finally {
          parameterDriverRow = -1;
        }
        if (i > 0 && ImGui.smallButton("Move Up")) up = i;
        if (i > 0) ImGui.sameLine();
        if (i + 1 < drivers.size() && ImGui.smallButton("Move Down")) down = i;
        if (i + 1 < drivers.size()) ImGui.sameLine();
        if (ImGui.smallButton("Remove")) remove = i;
        ImGui.treePop();
      }
      ImGui.popID();
    }
    if (remove >= 0) drivers.remove(remove);
    else if (up >= 0) Collections.swap(drivers, up, up - 1);
    else if (down >= 0) Collections.swap(drivers, down, down + 1);
    if (drivers.size() < 32 && ImGui.button("Add Parameter Driver")) {
      var d = new NumericDriver();
      d.enabled = true;
      drivers.add(d);
    }
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

  private static final class SeedText {
    final ImString buffer;
    long model;
    boolean invalid;

    SeedText(String text, long value) {
      buffer = new ImString(text, DraftSeedInputs.MAX_CHARACTERS + 1);
      model = value;
    }
  }

  private final LinkedHashMap<String, SeedText> seedTexts = new LinkedHashMap<>(16, .75f, true);
  private Object seedOwner;
  private final Map<String, float[]> seedCenters = new HashMap<>();
  private boolean probeSeedTop;

  void probeSeedProblems() {
    probeSeedTop = true;
  }

  float[] probeSeedControl(String key) {
    return seedCenters.get(key);
  }

  private void synchronizeSeeds() {
    state.seedInputs.synchronize(state);
    if (seedOwner != state.seedInputs.owner()) {
      seedOwner = state.seedInputs.owner();
      seedTexts.clear();
      seedCenters.clear();
    }
  }

  private void seedPrefix(String prefix, boolean orphan) {
    synchronizeSeeds();
    if (orphan) state.seedInputs.orphan(state, prefix);
    else state.seedInputs.discardPrefix(state, prefix);
    seedTexts.keySet().removeIf(key -> DraftSeedInputs.within(key, prefix));
  }

  private void changedGateSeeds() {
    if (worldSettingPath != null) seedPrefix("settings/" + worldSettingPath, true);
  }

  private void seedProblems() {
    synchronizeSeeds();
    var problems = state.seedInputs.problems(state);
    if (problems.isEmpty()) return;
    ImGui.textWrapped(
        "! "
            + problems.size()
            + " seed input(s) need correction or explicit restoration before Apply.");
    if (ImGui.smallButton("Restore All Seed Text")) {
      state.seedInputs.clear(state);
      seedTexts.clear();
    }
    var restoreMin = ImGui.getItemRectMin();
    var restoreMax = ImGui.getItemRectMax();
    seedCenters.put(
        "restoreAll",
        new float[] {(restoreMin.x + restoreMax.x) / 2, (restoreMin.y + restoreMax.y) / 2});
    int shown = 0;
    for (var problem : problems) {
      if (shown++ == 8) {
        ImGui.textDisabled("Restore or correct the shown inputs to reveal more.");
        break;
      }
      ImGui.pushID(problem.id());
      if (ImGui.smallButton("Restore")) {
        state.seedInputs.discard(state, problem.id());
        seedTexts.remove(problem.path());
      }
      ImGui.sameLine();
      if (ImGui.smallButton("Go To Seed"))
        goToProblem(
            EditorEvents.Target.diagnostic(
                state.project().project.uuid, problem.diagnostic(state.selected)));
      ImGui.sameLine();
      String label =
          problem.label()
              + ": "
              + problem.text()
              + (problem.orphan() ? " (changed gate tree)" : "");
      ImGui.text(AppMetadata.uiText(label.length() > 80 ? label.substring(0, 79) + "…" : label));
      if (ImGui.isItemHovered()) ImGui.setTooltip(problem.path() + "\n" + label);
      ImGui.popID();
    }
  }

  private void longText(String label, long value, java.util.function.LongConsumer set) {
    // Smoke-only standalone controls have no Properties transaction to own raw input.
    if (state.draft == null) {
      f.text(
          label,
          Long.toString(value),
          text -> {
            try {
              set.accept(Long.parseLong(text));
            } catch (NumberFormatException invalid) {
              state.status = "A signed 64-bit seed is required";
            }
          });
      return;
    }
    synchronizeSeeds();
    String key =
        worldSettingPath == null
            ? "seedOffset"
            : "settings/"
                + worldSettingPath
                + (worldSettingPath.equals("seedOffset") ? "" : "/seedOffset");
    String pending = state.seedInputs.text(key);
    SeedText input = seedTexts.get(key);
    if (input == null) {
      if (seedTexts.size() >= DraftSeedInputs.MAX_FIELDS)
        seedTexts.remove(seedTexts.keySet().iterator().next());
      input = new SeedText(pending == null ? Long.toString(value) : pending, value);
      input.invalid = pending != null;
      seedTexts.put(key, input);
    } else if (pending != null) {
      if (!input.buffer.get().equals(pending)) input.buffer.set(pending);
      input.invalid = true;
    } else if (input.model != value || input.invalid) {
      input.buffer.set(Long.toString(value));
      input.model = value;
      input.invalid = false;
    }
    if (ImGui.inputText(label, input.buffer)) {
      try {
        var parsed = state.seedInputs.edit(state, key, label, input.buffer.get());
        input.invalid = parsed.isEmpty();
        if (parsed.isPresent()) {
          set.accept(parsed.getAsLong());
          input.model = parsed.getAsLong();
        }
        noiseChanged();
      } catch (RuntimeException invalid) {
        input.buffer.set(
            Objects.requireNonNullElse(state.seedInputs.text(key), Long.toString(value)));
        input.invalid = state.seedInputs.text(key) != null;
        state.status = invalid.getMessage();
      }
    }
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    seedCenters.put(key, new float[] {a.x + Math.min(80, (b.x - a.x) * .25f), (a.y + b.y) / 2});
  }
}
