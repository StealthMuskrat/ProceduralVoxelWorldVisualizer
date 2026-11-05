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
import java.util.concurrent.Executor;
import java.util.function.*;

public final class PropertyPanel implements AutoCloseable {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(480, 720);
  final PanelDockControls docking = new PanelDockControls("Properties");
  private final EditorState state;
  private final PropertiesApplySession applySession;
  final SettingsPageChrome chrome = new SettingsPageChrome(3);
  final PropertyActionOwner actionOwner = new PropertyActionOwner();
  private boolean revealProblems, footerEditable;
  private Float probeBodyScroll;
  private float bodyScroll;
  private int bodySelectionCount = -1;

  final VoxelIdentityReview voxelIdentity = new VoxelIdentityReview();
  private float[] identityInput;
  private final UiFields f = new UiFields(this::noiseChanged);
  private final Map<Integer, UiFields.NumericRow> numericRows = new LinkedHashMap<>();
  private long noiseVersion;
  private final NoiseDraftIndex.Cache noiseIndex = new NoiseDraftIndex.Cache();
  private final NoiseItemBrowser noiseBrowser = new NoiseItemBrowser();
  private final DraftChoicePicker draftChoices;
  private NoiseEditDialog noiseEdit;
  private ExpressionEditDialog expressionDialog;
  private SearchNavigation referenceNavigation;
  final NavigationGuardDialog referenceDialog;
  private boolean referencePrompt;
  private String authoringMessage = "";
  private final Map<String, String> inputNames = new HashMap<>();
  private final Map<String, List<DraftChoicePicker.Choice>> catalogues = new HashMap<>();
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
  private float[] membershipOpenButton;
  private final Executor privateExecutor;
  private final Consumer<Runnable> privateUi;
  private final LongSupplier feedbackClock;
  private final BackgroundExpressionFeedback expressionChecking;
  private boolean expressionSeen;
  private final ExpressionFeedback expressionFeedback = new ExpressionFeedback();
  private final Map<String, String> conditionErrors = new HashMap<>();
  private long worldControlVersion;

  record WorldListControl(int id, DialogBounds.Rect bounds, boolean enabled) {}

  private final Map<String, WorldListControl> worldListControls = new LinkedHashMap<>();

  Map<String, WorldListControl> probeWorldListControls() {
    return Map.copyOf(worldListControls);
  }

  void probeWorldListPanel(String property) {
    if (!Set.of("samplingTransforms", "parameterDrivers", "typeIntervals").contains(property))
      throw new IllegalArgumentException("Not an ordered World property");
    numericRows.clear();
    worldControlCenters.clear();
    worldListControls.clear();
    synchronizeWorldControls();
    boolean previous = probingWorldNumbers;
    probingWorldNumbers = true;
    ImGui.pushID(property);
    try {
      editSetting(((Layer) state.draft).defaults, property);
    } finally {
      ImGui.popID();
      probingWorldNumbers = previous;
    }
  }

  private void synchronizeWorldControls() {
    if (!Objects.equals(last, state.selected) || lastDraft != state.draft) {
      last = state.selected;
      lastDraft = state.draft;
      worldStructureChanged();
    }
  }

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
  private final Map<UUID, float[]> pinCreationCenters = new HashMap<>();

  float[] probePinCreation(UUID item) {
    return pinCreationCenters.get(item);
  }

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
  private boolean probingWorldNumbers;
  private final Map<String, float[]> worldControlCenters = new HashMap<>();

  void probeWorldNumericRows(boolean value) {
    probingWorldNumbers = value;
  }

  float[] probeWorldControl(String key) {
    return worldControlCenters.get(key);
  }

  private void worldControl(String key, Runnable draw) {
    worldControl(key, draw, () -> ImGui.isItemActive() || ImGui.isItemFocused());
  }

  private void worldControl(String key, Runnable draw, BooleanSupplier focused) {
    worldControl(key, draw, focused, true);
  }

  private void worldControl(String key, Runnable draw, BooleanSupplier focused, boolean guidance) {
    propertyControl(null, key, draw, focused);
    var seed = seedRows.get(key);
    var bounds = seed == null ? PopupLayout.itemBounds() : seed.input();
    worldControlCenters.put(
        key,
        new float[] {
          bounds.x() + Math.min(80, bounds.width() * .25f), bounds.y() + bounds.height() / 2
        });
    if (guidance) worldMetadataTooltip(key, "");
  }

  private void worldMetadataTooltip(String key, String detail) {
    String id = WorldControlMetadata.topic(key);
    if (id == null) return;
    var control = state.metadata.control(id);
    if (control == null) return;
    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
      UiScale.tooltip(
          control.tooltip + (detail.isEmpty() ? "" : "\n" + detail) + "\nRight-click for Help.");
    contextHelp.item(id, id + ":world:" + key, true);
  }

  private void settingControl(String leaf, Runnable draw) {
    settingControl(leaf, draw, () -> ImGui.isItemActive() || ImGui.isItemFocused());
  }

  private void settingControl(String leaf, Runnable draw, BooleanSupplier focused) {
    settingControl(leaf, draw, focused, true);
  }

  private void settingNumericControl(String leaf, Runnable draw) {
    settingControl(leaf, draw, () -> ImGui.isItemActive() || ImGui.isItemFocused(), false);
  }

  private void settingControl(
      String leaf, Runnable draw, BooleanSupplier focused, boolean guidance) {
    if (worldSettingPath == null) {
      draw.run();
      return;
    }
    worldControl(
        "settings/" + worldSettingPath + (leaf.isEmpty() ? "" : "/" + leaf),
        draw,
        focused,
        guidance);
  }

  private void settingNumber(
      String leaf, String label, double value, double minimum, double maximum, DoubleConsumer set) {
    String id = WorldControlMetadata.setting(worldSettingPath, leaf);
    if (id == null)
      numeric(
          f, label, value, minimum, maximum, false, set, draw -> settingNumericControl(leaf, draw));
    else
      numeric(
          f,
          label,
          value,
          minimum,
          maximum,
          false,
          set,
          draw -> settingNumericControl(leaf, draw),
          id);
  }

  private void settingInteger(
      String leaf, String label, int value, int minimum, int maximum, IntConsumer set) {
    String id = WorldControlMetadata.setting(worldSettingPath, leaf);
    int[] displayed = {value};
    IntConsumer publish =
        next -> {
          set.accept(next);
          displayed[0] = next;
        };
    var row =
        f.integer(
            label,
            value,
            minimum,
            maximum,
            publish,
            draw -> settingNumericControl(leaf, draw),
            id == null
                ? null
                : nativeId -> numericGuidance(id, nativeId, displayed[0], minimum, maximum));
    numericRows.put(row.inputId(), row);
  }

  private boolean settingTree(String label) {
    String key = "settings/" + worldSettingPath;
    if (probingWorldNumbers) ImGui.setNextItemOpen(true, ImGuiCond.Always);
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
        String pinPath = PinBrowser.controlPath(target);
        boolean precise =
            target.control() != null
                && (pinPath == null
                    ? pinBrowser.selectTarget(target.control())
                    : pinBrowser.selectTarget(target.control(), pinPath));
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
    return privateEditorOpen()
        || referenceNavigation.pending()
        || applySession != null && applySession.running();
  }

  private boolean privateEditorOpen() {
    return curve != null
        || membershipDialog != null
        || noiseEdit != null
        || expressionDialog != null;
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
            },
            privateExecutor,
            privateUi);
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
      boolean focused = acknowledged.getAsBoolean();
      if (submittedNow && !focused && !ImGui.isItemVisible())
        propertyFocus.retryAfterClipping(control, parameter);
      if (focused) {
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
    this(s, null, null);
  }

  static PropertyPanel background(EditorState state) {
    return new PropertyPanel(state, state.background, state.uiQueue::offer);
  }

  PropertyPanel(EditorState s, Executor executor, Consumer<Runnable> ui) {
    this(s, executor, ui, System::nanoTime);
  }

  PropertyPanel(
      EditorState s, Executor executor, Consumer<Runnable> ui, LongSupplier feedbackClock) {
    state = s;
    privateExecutor = executor;
    privateUi = ui;
    this.feedbackClock = Objects.requireNonNull(feedbackClock);
    expressionChecking =
        executor == null
            ? null
            : new BackgroundExpressionFeedback(s, executor, ui, feedbackClock, null);
    contextHelp = new ContextHelp(s.helpRequests);
    resourcePicker = new ResourcePicker(s, () -> state.draft, () -> noiseVersion);
    draftChoices = new DraftChoicePicker(s, () -> state.draft, () -> noiseVersion);
    referenceNavigation =
        executor == null
            ? new SearchNavigation(s)
            : new SearchNavigation(
                s,
                executor,
                ui,
                value ->
                    completedReferenceNavigation(value.target(), value.result(), value.applied()));
    referenceDialog = new NavigationGuardDialog(s);
    applySession =
        executor == null
            ? null
            : new PropertiesApplySession(
                s, executor, ui, () -> privateEditorOpen() || referenceNavigation.pending());
  }

  @Override
  public void close() {
    if (curve != null) curve.cancel();
    if (noiseEdit != null) noiseEdit.close();
    if (expressionChecking != null) expressionChecking.close();
    if (expressionDialog != null) expressionDialog.close();
    if (membershipDialog != null) membershipDialog.close();
    if (applySession != null) applySession.close();
    referenceNavigation.close();
  }

  PropertiesApplySession probeApplySession() {
    return applySession;
  }

  MembershipDialog probeMembershipDialog() {
    return membershipDialog;
  }

  float[] probeMembershipOpenButton() {
    return membershipOpenButton;
  }

  ResourcePicker probeResourcePicker() {
    return resourcePicker;
  }

  DraftChoicePicker probeDraftPicker() {
    return draftChoices;
  }

  public void draw() {
    expressionSeen = false;
    membershipOpenButton = null;
    if (applySession != null) applySession.view();
    if (!(state.draft instanceof VoxelType) || state.multiSelection.size() != 1)
      voxelIdentity.clear();
    identityInput = null;
    numericRows.clear();
    footerEditable = false;
    seedCenters.clear();
    seedRows.clear();
    worldControlCenters.clear();
    worldListControls.clear();
    parameterCenters.clear();
    pinCreationCenters.clear();
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
    long actionVersion = actionOwner.synchronize(state, searchBlocked());
    windowLayout.prepareExisting();
    docking.prepare();
    boolean visible = ImGui.begin("Properties");
    UiScale.window(state.user, "Properties");
    windowLayout.observe();
    DialogLayout.observe(state, "Properties");
    if (!visible) voxelIdentity.clear();
    if (visible) {
      boolean changedBody =
          !Objects.equals(last, state.selected)
              || lastDraft != state.draft
              || bodySelectionCount != state.multiSelection.size();
      bodySelectionCount = state.multiSelection.size();
      docking.draw();
      chrome.begin("properties-body");
      if (applySession != null && !applySession.view().message().isEmpty())
        ImGui.textWrapped(StatusBarText.clip(applySession.view().message(), 3072));
      if (changedBody || revealProblems) {
        ImGui.setScrollY(0);
        revealProblems = false;
      }
      if (probeBodyScroll != null) {
        ImGui.setScrollY(probeBodyScroll);
        probeBodyScroll = null;
      }
      if (probeSeedTop) {
        ImGui.setScrollY(0);
        probeSeedTop = false;
      }
      synchronizeWorldControls();
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
        if (searchBlocked())
          ImGui.textWrapped(
              "Finish or cancel the private editor before applying or reverting Properties.");
        if (!state.issues.isEmpty()) {
          ThemeStyle.text("Error", "! " + state.issues.size() + " validation issue(s)");
          for (int i = 0; i < Math.min(8, state.issues.size()); i++) {
            var d = state.issues.get(i);
            ImGui.textWrapped(StatusBarText.clip(d.toString(), 2048));
            var target = EditorEvents.Target.diagnostic(state.project().project.uuid, d);
            if (state.canNavigate(target)
                && !target.settings()
                && ImGui.smallButton("Go To Problem##inline" + i)) goToProblem(target);
          }
          if (state.issues.size() > 8)
            ImGui.textDisabled("More results are available in View > Validate Project.");
        }
        ImGui.separator();
        if (state.draft instanceof Identity r) {
          propertyControl(null, "id", () -> f.text("ID", r.id, v -> r.id = v));
          var rect = PopupLayout.itemBounds();
          identityInput = new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2};
        }
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
          if (ImGui.collapsingHeader(
              "Voxel Identity and References", ImGuiTreeNodeFlags.DefaultOpen))
            voxelIdentity.draw(
                state,
                t.uuid,
                t.id,
                Math.max(
                    3 * ImGui.getFrameHeightWithSpacing(),
                    Math.min(320, Math.max(140, 9 * ImGui.getFrameHeightWithSpacing()))),
                findReferences);
          else voxelIdentity.clear();
          float[] rgb = {t.color.r / 255f, t.color.g / 255f, t.color.b / 255f};
          if (UiScale.colorEdit3("Color", rgb)) {
            t.color.r = Math.round(rgb[0] * 255);
            t.color.g = Math.round(rgb[1] * 255);
            t.color.b = Math.round(rgb[2] * 255);
          }
          ImGui.textWrapped(
              "Palette edits affect appearance; semantic voxel identity remains the same.");
        } else if (state.draft instanceof Membership m) {
          ImGui.text(m.voxelTypeUuids.size() + " member reference(s)");
          if (ImGui.button("Edit Members..."))
            membershipDialog = new MembershipDialog(state, m, privateExecutor, privateUi);
          var memberBounds = PopupLayout.itemBounds();
          membershipOpenButton =
              new float[] {
                memberBounds.x() + memberBounds.width() / 2,
                memberBounds.y() + memberBounds.height() / 2
              };
          contextHelp.item("help.membership-apply");
          ImGui.textWrapped(
              "Membership editing offers a temporary searchable selection with Apply/Cancel.");
        } else if (state.draft instanceof Folder folder) {
          ImGui.text("Organizational folder: " + folder.rootKind);
          ImGui.textWrapped(
              "Folder placement never changes generation or adds resource inheritance.");
        }
      }
      bodyScroll = ImGui.getScrollY();
      chrome.end();
      drawActions(actionVersion);
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
    if (expressionChecking != null && !expressionSeen) expressionChecking.suspend();
    drawSmokeWorldControls();
  }

  private void drawActions(long version) {
    boolean applying = applySession != null && applySession.running();
    boolean blocked = searchBlocked();
    footerEditable = actionOwner.editable(state, blocked);
    boolean problems = !state.issues.isEmpty() || state.seedInputs.pending(state);
    ImGui.pushID("properties-actions:" + version);
    ImGui.beginDisabled(!footerEditable && !applying);
    boolean apply = chrome.identifiedAction("apply", applying ? "Cancel Apply" : "Apply");
    f.tooltip(
        applying
            ? "Cancel the pending Apply and keep this edit private."
            : "Apply validates this edit and commits one undoable transaction.");
    contextHelp.item("help.properties-apply", "properties-apply", true);
    ImGui.endDisabled();
    ImGui.beginDisabled(!footerEditable);
    boolean revert = chrome.identifiedAction("revert", "Revert");
    f.tooltip("Revert reloads the selected resource and discards its private edits.");
    ImGui.endDisabled();
    ImGui.beginDisabled(!problems);
    boolean show = chrome.identifiedAction("problems", "Show Problems");
    ImGui.endDisabled();
    ImGui.popID();
    // Escape does not discard this parent draft or authorize a simultaneous document action.
    if (applying) {
      if (apply || ImGui.isKeyPressed(ImGuiKey.Escape, false)) applySession.cancel();
    } else if (!ImGui.isKeyPressed(ImGuiKey.Escape, false)
        && actionOwner.editable(state, searchBlocked())) {
      if (revert) {
        state.refreshDraft();
        f.clear();
        revealProblems();
      } else if (apply) {
        if (applySession == null) {
          if (!state.applyDraft()) revealProblems();
        } else if (!applySession.start()) revealProblems();
      }
    }
    if (show && problems) revealProblems();
  }

  private void revealProblems() {
    revealProblems = true;
    propertyFocus.cancel();
    expressionSelection.cancel();
    searchScroll = false;
    focusedControl = null;
  }

  float[] probeIdentityInput() {
    return identityInput == null ? null : identityInput.clone();
  }

  float[] probeAction(String key) {
    var box = chrome.actions().get(key);
    return box == null ? null : new float[] {box.x() + box.width() / 2, box.y() + box.height() / 2};
  }

  boolean probeFooterEditable() {
    return footerEditable;
  }

  void probeBodyScroll(float value) {
    probeBodyScroll = value;
  }

  float probeBodyScroll() {
    return bodyScroll;
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
          if (PinControls.add(n, p.uuid, "", p.id)) noiseChanged();
        }
        contextHelp.item("help.pins", "pin-shared-parameter");
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
      if (UiScale.beginPopup("new-parameter")) {
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
    if (privateExecutor != null) {
      noiseEdit =
          new NoiseEditDialog(
              state,
              noise,
              request,
              selected -> afterNoiseEdit(noise, selected),
              privateExecutor,
              privateUi,
              true);
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

  void probeNoiseAction(NoiseAuthoring.Request request) {
    requestNoiseEdit((Noise) state.draft, request);
  }

  NoiseEditDialog probeNoiseEditDialog() {
    return noiseEdit;
  }

  void probeNoiseEditPanel() {
    if (noiseEdit != null && noiseEdit.draw()) noiseEdit = null;
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
    UiScale.beginChild("local-users", 0, 120, true);
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
            || !Objects.equals(pinFocusControl, pinFocusPin.targetUuid)
            || PinBrowser.controlPath(navigationTarget) != null
                && !Objects.equals(
                    PinBrowser.controlPath(navigationTarget), pinFocusPin.control))) {
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
    boolean expanded = ImGui.collapsingHeader("Pinned Controls");
    pinRect("header");
    contextHelp.item("help.pins", "pinned-controls");
    if (!expanded) return;
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
      } else if (pin.control != null && !pin.control.isEmpty()) {
        pinnedItemControl(noise, item, pin);
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
      String destination =
          row.section() == NoiseDraftIndex.Section.PARAMETERS
                  || pin.control == null
                  || pin.control.isEmpty()
              ? PinSources.control(row, item)
              : PinControls.resolvedPath(item, pin.control);
      ImGui.beginDisabled(destination == null);
      if (ImGui.smallButton("Go To Control")) {
        if ("parameters/curve".equals(destination)) {
          try {
            openCurve(item, null);
          } catch (RuntimeException invalid) {
            state.notify(
                "Error",
                "Curve cannot open: "
                    + invalid.getMessage()
                    + ". Correct the definition or reset the curve.");
          }
        } else focusLocal(noise, pin.targetUuid, destination);
      }
      ImGui.endDisabled();
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

  private void pinnedItemControl(Noise noise, Item item, Pin pin) {
    String path = PinControls.resolvedPath(item, pin.control);
    if (path == null) {
      ImGui.textWrapped("This control is missing. Unpin it or repair the original item.");
      return;
    }
    String key = path.substring(path.indexOf('/') + 1);
    if (path.startsWith("inputs/")) {
      var binding = item.inputs.get(key);
      ImGui.textWrapped(key + ": " + PinSources.input(noise, state.project(), binding));
      if (binding != null
          && "Constant".equals(binding.sourceKind)
          && binding.type != null
          && Validator.TYPES.contains(binding.type))
        constantEditor(
            pinFields,
            binding.type,
            binding.value,
            value -> binding.value = value,
            (component, draw) -> {
              draw.run();
              pinRect(component);
            });
      return;
    }
    var value = item.parameters.get(key);
    if (item.kind.equals("CONSTANT") && key.equals("value")) {
      constantEditor(
          pinFields,
          item.resultType,
          value,
          next -> item.parameters.put(key, next),
          (component, draw) -> {
            draw.run();
            pinRect(component);
          });
    } else if (value != null && value.isBoolean()) {
      pinFields.bool(
          key,
          value.booleanValue(),
          next -> item.parameters.put(key, ProjectIO.JSON.valueToTree(next)));
      pinRect("value");
    } else if (value != null && value.isNumber()) {
      String id =
          noise.coordinateTransforms.contains(item)
              ? "transform" + noise.dimension + "D." + item.kind + "." + key
              : "processing." + item.kind + ".parameter." + key;
      var spec = CreationRegistry.CONTROLS.get(id);
      numeric(
          pinFields,
          state.metadata.label(id, key),
          value.doubleValue(),
          spec == null ? -1 : Math.max(-1024, spec.minimum()),
          spec == null ? 1 : Math.min(1024, spec.maximum()),
          spec != null && spec.kind() == CreationRegistry.Kind.INTEGER,
          next -> item.parameters.put(key, ProjectIO.JSON.valueToTree(next)),
          draw -> {
            draw.run();
            pinRect("value");
          },
          id);
    } else {
      ImGui.textWrapped("Edit " + key + " at the original control.");
    }
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
              new ExpressionEditSession(
                  state, noise, item, privateExecutor, privateUi, feedbackClock),
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
    var feedback = inspectExpressionFeedback(n, item);
    expressionSelection.context(state.project(), state.draft, feedbackSelectionOwner(item), expr);
    if (expressionTarget && !expressionFocusSubmitted) {
      boolean userMoved =
          expressionChecking != null
              && (ImGui.isMouseClicked(ImGuiMouseButton.Left)
                  || ImGui.isMouseClicked(ImGuiMouseButton.Right)
                  || ImGui.isMouseClicked(ImGuiMouseButton.Middle)
                  || ImGui.isKeyPressed(ImGuiKey.Home, false)
                  || ImGui.isKeyPressed(ImGuiKey.End, false)
                  || ImGui.isKeyPressed(ImGuiKey.PageUp, false)
                  || ImGui.isKeyPressed(ImGuiKey.PageDown, false)
                  || ImGui.isKeyPressed(ImGuiKey.Enter, false)
                  || ImGui.isKeyPressed(ImGuiKey.Escape, false)
                  || ImGui.isKeyPressed(ImGuiKey.Tab, false)
                  || ImGui.isKeyPressed(ImGuiKey.LeftArrow, false)
                  || ImGui.isKeyPressed(ImGuiKey.RightArrow, false)
                  || ImGui.isKeyPressed(ImGuiKey.UpArrow, false)
                  || ImGui.isKeyPressed(ImGuiKey.DownArrow, false));
      if (userMoved) searchScroll = false;
      else if (feedback != null) {
        expressionSelection.request(expr, feedback);
        ImGui.setKeyboardFocusHere();
        expressionFocusSubmitted = true;
      }
    }
    f.text(
        "Expression",
        expr,
        v -> item.parameters.put("expression", ProjectIO.JSON.valueToTree(v)),
        expressionSelection);
    expressionRect("text");
    expressionActive = ImGui.isItemActive();
    String shown = ExpressionFeedback.source(item);
    feedback = inspectExpressionFeedback(n, item);
    expressionSelection.context(state.project(), state.draft, feedbackSelectionOwner(item), shown);
    if (!expr.equals(shown)) {
      searchScroll = false;
      expressionFocusSubmitted = false;
    }
    if (expressionTarget && searchScroll && expressionFocusSubmitted) {
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
    if (feedback == null) {
      var checking = expressionChecking.view();
      ImGui.textWrapped(StatusBarText.clip(checking.message(), 640));
      if (checking.retryable()) {
        if (ImGui.smallButton("Retry checking")) expressionChecking.retry();
        expressionRect("retry");
      }
      return;
    }
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
          UiScale.tooltip(
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

  private ExpressionFeedback.Result inspectExpressionFeedback(Noise noise, Item item) {
    expressionSeen = true;
    return expressionChecking == null
        ? expressionFeedback.inspect(state.project(), noise, item)
        : expressionChecking.observe(noise, item).result();
  }

  private Object feedbackSelectionOwner(Item item) {
    return expressionChecking == null ? item : expressionChecking.selectionOwner();
  }

  BackgroundExpressionFeedback probeExpressionFeedback() {
    return expressionChecking;
  }

  ExpressionEditSession probeExpandedExpressionSession() {
    return expressionDialog == null ? null : expressionDialog.session();
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
    return referenceDialog.button(id);
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
    if (!referenceNavigation.pending()) {
      referencePrompt = false;
      referenceDialog.close("Leave Noise Property Draft");
      return;
    }
    if (referencePrompt) {
      referenceDialog.open("Leave Noise Property Draft");
      referencePrompt = false;
    }
    referenceDialog.draw(
        "Leave Noise Property Draft",
        "Apply the current draft, discard it for this navigation, or cancel.",
        referenceNavigation,
        this::decideReferenceNavigation);
  }

  void probeAcceptReferenceNavigation() {
    referenceNavigation.acceptPrepared();
  }

  SearchNavigation.Result decideReferenceNavigation(SearchNavigation.Decision decision) {
    if (!referenceNavigation.pending()) return SearchNavigation.Result.UNAVAILABLE;
    var target = referenceNavigation.target();
    long owner = referenceNavigation.version();
    boolean[] applied = {false};
    try {
      var result =
          referenceNavigation.decide(
              decision,
              () -> !privateEditorOpen(),
              () -> {
                applied[0] = true;
                return !privateEditorOpen();
              });
      completedReferenceNavigation(target, result, applied[0]);
      return result;
    } catch (RuntimeException failure) {
      if (referenceNavigation.pending() && referenceNavigation.version() == owner)
        referenceNavigation.clear();
      if (!referenceNavigation.pending()) referencePrompt = false;
      referenceFailure("REFERENCE_NAVIGATION_FAILED", failure, applied[0]);
      return SearchNavigation.Result.UNAVAILABLE;
    }
  }

  private void completedReferenceNavigation(
      EditorEvents.Target target, SearchNavigation.Result result, boolean applied) {
    if (!referenceNavigation.pending()) referencePrompt = false;
    if (result == SearchNavigation.Result.NAVIGATED && target != null) {
      try {
        goToProblem(target);
      } catch (RuntimeException failure) {
        referenceFailure("REFERENCE_REVEAL_FAILED", failure, applied);
      }
    } else if (result == SearchNavigation.Result.UNAVAILABLE)
      authoringMessage =
          (applied ? "Draft applied. " : "")
              + "Navigation became stale or a property editor is open; finish that editor and"
              + " request the reference again.";
  }

  private void referenceFailure(String code, RuntimeException failure, boolean applied) {
    String detail = failure.getMessage();
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    authoringMessage =
        StatusBarText.clip((applied ? "Draft applied; navigation failed: " : "") + detail, 1024);
    state.error(code, EditorEvents.Subsystem.APPLICATION, authoringMessage);
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

  private final ContextHelp contextHelp;

  ContextHelp contextHelp() {
    return contextHelp;
  }

  private void metadataTooltip(String id) {
    var control = state.metadata.control(id);
    if (control != null && ImGui.isItemHovered())
      UiScale.tooltip(control.tooltip + "\nRight-click for Help.");
    if (control != null) contextHelp.item(id);
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
      numeric(
          f,
          state.metadata.label(id, control.label()),
          current,
          control.presetMinimum(),
          control.presetMaximum(),
          control.integer(),
          value -> noise.algorithm.parameters.put(key, value),
          draw -> propertyControl(null, "algorithm/parameters/" + key, draw),
          id);
      ImGui.popID();
    }
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    sourceControlCenters.put(
        key,
        control.choices().isEmpty()
            ? new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2}
            : draftChoices.buttonCenter());
    if (!control.choices().isEmpty()) metadataTooltip(id);
    revealSearchField(null, "algorithm/parameters/" + key);
  }

  private final Map<String, float[]> parameterCenters = new HashMap<>();

  float[] probeParameter(UUID item, String key) {
    return parameterCenters.get(item + ":" + key);
  }

  List<DraftChoicePicker.Choice> probeOperations(Noise noise) {
    return catalogue(noise, "processing", OPERATIONS);
  }

  Map<Integer, UiFields.NumericRow> probeNumericRows() {
    return Map.copyOf(numericRows);
  }

  private void numeric(
      UiFields widgets,
      String label,
      double value,
      double minimum,
      double maximum,
      boolean integer,
      DoubleConsumer set,
      Consumer<Runnable> input) {
    var row = widgets.number(label, value, minimum, maximum, integer, set, input);
    numericRows.put(row.inputId(), row);
  }

  private void numeric(
      UiFields widgets,
      String label,
      double value,
      double minimum,
      double maximum,
      boolean integer,
      DoubleConsumer set,
      Consumer<Runnable> input,
      String metadataId) {
    double[] displayed = {value};
    DoubleConsumer publish =
        next -> {
          set.accept(next);
          displayed[0] = next;
        };
    var row =
        widgets.number(
            label,
            value,
            minimum,
            maximum,
            integer,
            publish,
            publish,
            input,
            nativeId -> numericGuidance(metadataId, nativeId, displayed[0], minimum, maximum));
    numericRows.put(row.inputId(), row);
  }

  private void numericGuidance(
      String metadataId, int nativeId, double value, double minimum, double maximum) {
    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
      UiScale.tooltip(
          NumericGuidance.from(state.metadata, metadataId, value, minimum, maximum).text());
    if (state.metadata.control(metadataId) != null)
      contextHelp.item(metadataId, NumericGuidance.identity(metadataId, nativeId), true);
  }

  private void parameter(Item item, String key, double def) {
    var noise = (Noise) state.draft;
    boolean coordinate = noise.coordinateTransforms.contains(item);
    String id =
        coordinate
            ? "transform" + noise.dimension + "D." + item.kind + "." + key
            : "processing." + item.kind + ".parameter." + key;
    var spec = CreationRegistry.CONTROLS.get(id);
    // Creation metadata contains finite authoring domains, including effectively unbounded
    // engine controls. Sliders suggest a usable neighborhood; exact text retains that full domain.
    double minimum = spec == null ? -1 : Math.max(-1024, spec.minimum());
    double maximum = spec == null ? 1 : Math.min(1024, spec.maximum());
    numeric(
        f,
        state.metadata.label(id, key),
        item.parameters.containsKey(key) ? item.parameters.get(key).asDouble() : def,
        minimum,
        maximum,
        spec != null && spec.kind() == CreationRegistry.Kind.INTEGER,
        v -> item.parameters.put(key, ProjectIO.JSON.valueToTree(v)),
        draw -> propertyControl(item.uuid, "parameters/" + key, draw),
        id);
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    parameterCenters.put(
        item.uuid + ":" + key,
        new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
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
      if (PinControls.add(noise, uuid, "", list.get(i).id)) noiseChanged();
    }
    contextHelp.item("help.pins", "pin-item");
    var item = list.get(i);
    var pinChoices =
        PinControls.paths(item).stream()
            .map(
                path ->
                    new DraftChoicePicker.Choice(
                        path,
                        (path.startsWith("inputs/") ? "Input: " : "Parameter: ")
                            + path.substring(path.indexOf('/') + 1),
                        item.id))
            .toList();
    if (!pinChoices.isEmpty()) {
      draftChoices.draw(
          "Pin Control",
          null,
          pinChoices,
          null,
          path -> {
            if (PinControls.add(
                noise, uuid, path, item.id + " / " + path.substring(path.indexOf('/') + 1)))
              noiseChanged();
          });
      pinCreationCenters.put(uuid, draftChoices.buttonCenter());
      contextHelp.item("help.pins", "pin-control-picker");
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
        numeric(
            f,
            label,
            current.asDouble(),
            control.presetMinimum(),
            control.presetMaximum(),
            control.integer(),
            value -> item.parameters.put(key, ProjectIO.JSON.valueToTree(value)),
            draw -> propertyControl(item.uuid, "parameters/" + key, draw),
            id);
        var lo = ImGui.getItemRectMin();
        var hi = ImGui.getItemRectMax();
        parameterCenters.put(
            item.uuid + ":" + key,
            new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
      }
      if (!control.options().isEmpty()) metadataTooltip(id);
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
      numeric(
          f,
          "Inserted slice coordinate",
          b.slice,
          -1024,
          1024,
          false,
          v -> b.slice = v,
          draw -> bindingField(item, b, "slice", draw));
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
      } else if (!ImGui.isItemVisible()) bindingFocusSubmitted = false;
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
        numeric(
            f,
            "Slice coordinate",
            b.slice,
            -1024,
            1024,
            false,
            v -> b.slice = v,
            draw -> bindingField(item, b, "slice", draw));
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
      numeric(
          widgets,
          "Value",
          value == null ? 0 : value.asDouble(),
          -1,
          1,
          false,
          v -> set.accept(ProjectIO.JSON.valueToTree(v)),
          draw -> field.accept("value", draw));
    else {
      int count = type.endsWith("2") || type.endsWith("2D") ? 2 : 3;
      double[] vec = new double[count];
      for (int i = 0; i < count; i++)
        vec[i] = value != null && value.isArray() && value.size() > i ? value.get(i).asDouble() : 0;
      for (int i = 0; i < count; i++) {
        final int k = i;
        numeric(
            widgets,
            count == 2 ? (k == 0 ? "X" : "Z") : List.of("X", "Y", "Z").get(k),
            vec[k],
            -1,
            1,
            false,
            v -> {
              vec[k] = v;
              set.accept(ProjectIO.JSON.valueToTree(vec));
            },
            draw -> field.accept("value/" + k, draw));
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
        boolean reset = ImGui.smallButton("Reset to Layer Default");
        var resetBounds = PopupLayout.itemBounds();
        worldControlCenters.put(
            "reset/" + key,
            new float[] {
              resetBounds.x() + resetBounds.width() / 2, resetBounds.y() + resetBounds.height() / 2
            });
        worldMetadataTooltip("reset/" + key, "");
        if (reset) {
          seedPrefix("settings/" + key, false);
          op.overrides.remove(key);
        }
      } else {
        JsonNode node = ProjectIO.JSON.valueToTree(effective);
        ImGui.textWrapped(AppMetadata.settingLabel(key) + ": " + settingValue(key, node.get(key)));
        var inheritedBounds = PopupLayout.itemBounds();
        worldControlCenters.put(
            "inherited/" + key,
            new float[] {
              inheritedBounds.x() + inheritedBounds.width() / 2,
              inheritedBounds.y() + inheritedBounds.height() / 2
            });
        worldMetadataTooltip(
            "settings/" + key,
            node.get(key).isNumber()
                ? "Value: "
                    + (node.get(key).isFloatingPointNumber()
                        ? ScalarDisplay.exact(node.get(key).asDouble())
                        : node.get(key).asText())
                : "");
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
    if (value.isFloatingPointNumber()) return ScalarDisplay.compact(value.asDouble());
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
        double minimum =
            switch (key) {
              case "strength", "probability" -> 0;
              case "baseHeight" -> state.project().project.minY;
              case "heightScale" -> -256;
              case "scaleX", "scaleY", "scaleZ" -> -8;
              case "offsetX", "offsetY", "offsetZ" -> -1024;
              default -> -1;
            };
        double maximum =
            switch (key) {
              case "baseHeight" -> state.project().project.maxY;
              case "heightScale" -> 256;
              case "scaleX", "scaleY", "scaleZ" -> 8;
              case "offsetX", "offsetY", "offsetZ" -> 1024;
              default -> 1;
            };
        settingNumber(
            "",
            AppMetadata.settingLabel(key),
            value,
            minimum,
            maximum,
            v -> {
              try {
                field.setDouble(s, v);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
      } else if (field.getType() == int.class) {
        int value = field.getInt(s);
        settingInteger(
            "",
            AppMetadata.settingLabel(key),
            value,
            state.project().project.minY,
            state.project().project.maxY,
            v -> {
              try {
                field.setInt(s, v);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
      } else if (key.equals("seedOffset"))
        settingNumericControl(
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

  private void conditionEdit(BooleanSupplier edit, boolean gate) {
    try {
      if (edit.getAsBoolean()) {
        if (gate) changedGateSeeds();
        worldStructureChanged();
      }
    } catch (IllegalArgumentException ex) {
      conditionErrors.put(
          worldSettingPath, "This edit would exceed the supported condition depth.");
    }
  }

  private void conditionFeedback(boolean gate, int depth) {
    String problem = conditionErrors.get(worldSettingPath);
    if (problem != null) ImGui.textWrapped(problem);
    if (depth != 0) return;
    ImGui.textWrapped(
        "AND/OR groups retain existing clauses. Choosing a basic condition replaces subclauses.");
    if (gate)
      ImGui.textWrapped(
          "Regrouping gates can change path-based probability draws. NOT negates its child; Use"
              + " Only Child removes that negation.");
  }

  // Keep deep valid conditions reachable without multiplying indentation by all 32 levels.
  private boolean conditionTree(String label, int depth) {
    if (smokeWorldSettings != null || probingWorldNumbers)
      ImGui.setNextItemOpen(true, ImGuiCond.Always);
    return ImGui.treeNodeEx(label, depth >= 3 ? ImGuiTreeNodeFlags.NoTreePushOnOpen : 0);
  }

  private void predicate(Predicate p, int depth) {
    if (depth > WorldConditionEdits.MAX_DEPTH) {
      ImGui.textWrapped(
          "Condition exceeds the supported nesting depth; replace its parent to repair.");
      return;
    }
    ImGui.pushID("condition-values:" + worldControlVersion);
    settingControl(
        "kind",
        () ->
            f.choice(
                "Condition",
                p.kind,
                WorldConditionEdits.PREDICATES,
                v -> conditionEdit(() -> WorldConditionEdits.kind(p, v, depth), false)));
    if (p.kind.startsWith("TYPE_"))
      settingControl(
          "reference",
          () -> typePicker("Voxel type", p.reference, v -> p.reference = v),
          resourcePicker::focused);
    else if (p.kind.equals("IN_SET") || p.kind.equals("NOT_IN_SET"))
      settingControl(
          "reference",
          () -> resourcePicker.draw("Voxel set", "set", p.reference, null, v -> p.reference = v),
          resourcePicker::focused);
    ImGui.popID();
    conditionFeedback(false, depth);
    if (WorldConditionEdits.group(p.kind)) {
      int remove = -1;
      for (int i = 0; i < p.children.size(); i++) {
        ImGui.pushID(i);
        if (conditionTree("Clause " + (i + 1) + " (level " + (depth + 1) + ")", depth)) {
          var child = p.children.get(i);
          nestedSetting("children/" + i, () -> predicate(child, depth + 1));
          ImGui.pushID("condition-actions:" + worldControlVersion);
          ImGui.beginDisabled(p.children.size() <= 1);
          if (ImGui.smallButton("Remove")) remove = i;
          ImGui.endDisabled();
          ImGui.popID();
          if (depth < 3) ImGui.treePop();
        }
        ImGui.popID();
      }
      if (remove >= 0) {
        int index = remove;
        conditionEdit(() -> WorldConditionEdits.remove(p, index), false);
      }
      ImGui.pushID("condition-actions:" + worldControlVersion);
      ImGui.beginDisabled(depth >= WorldConditionEdits.MAX_DEPTH);
      if (ImGui.button("Add Clause")) conditionEdit(() -> WorldConditionEdits.add(p, depth), false);
      ImGui.endDisabled();
      if (p.children.size() == 1 && ImGui.button("Use Only Child"))
        conditionEdit(() -> WorldConditionEdits.unwrap(p), false);
      ImGui.popID();
    }
  }

  private void gate(Gate g, int depth) {
    if (depth > WorldConditionEdits.MAX_DEPTH) {
      ImGui.textWrapped("Gate exceeds the supported nesting depth; replace its parent to repair.");
      return;
    }
    ImGui.pushID("condition-values:" + worldControlVersion);
    settingControl(
        "kind",
        () ->
            f.choice(
                "Gate",
                g.kind,
                WorldSemantics.GATES,
                v -> conditionEdit(() -> WorldConditionEdits.kind(g, v, depth), true)));
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
      settingNumber("threshold", "Threshold / minimum", g.threshold, -1, 1, v -> g.threshold = v);
      if (g.kind.equals("INTERVAL")) {
        settingNumber("max", "Maximum", g.max, -1, 1, v -> g.max = v);
        settingControl(
            "includeMin", () -> f.bool("Include minimum", g.includeMin, v -> g.includeMin = v));
        settingControl(
            "includeMax", () -> f.bool("Include maximum", g.includeMax, v -> g.includeMax = v));
      }
    }
    if (g.kind.equals("PROBABILITY")) {
      settingNumber(
          "probability", "Gate probability [0,1]", g.probability, 0, 1, v -> g.probability = v);
      settingNumericControl(
          "seedOffset", () -> longText("Gate seed offset", g.seedOffset, v -> g.seedOffset = v));
      ImGui.textWrapped("Independent deterministic XYZ draw, stable within this gate path.");
    }
    ImGui.popID();
    conditionFeedback(true, depth);
    if (WorldConditionEdits.gateGroup(g.kind)) {
      int remove = -1;
      for (int i = 0; i < g.children.size(); i++) {
        ImGui.pushID(i);
        if (conditionTree("Gate clause " + (i + 1) + " (level " + (depth + 1) + ")", depth)) {
          var child = g.children.get(i);
          nestedSetting("children/" + i, () -> gate(child, depth + 1));
          ImGui.pushID("condition-actions:" + worldControlVersion);
          ImGui.beginDisabled(g.children.size() <= 1);
          if (ImGui.smallButton("Remove")) remove = i;
          ImGui.endDisabled();
          ImGui.popID();
          if (depth < 3) ImGui.treePop();
        }
        ImGui.popID();
      }
      if (remove >= 0) {
        int index = remove;
        conditionEdit(() -> WorldConditionEdits.remove(g, index), true);
      }
      ImGui.pushID("condition-actions:" + worldControlVersion);
      ImGui.beginDisabled(
          depth >= WorldConditionEdits.MAX_DEPTH || g.kind.equals("NOT") && !g.children.isEmpty());
      if (ImGui.button("Add Gate")) conditionEdit(() -> WorldConditionEdits.add(g, depth), true);
      ImGui.endDisabled();
      if (g.children.size() == 1 && ImGui.button("Use Only Child"))
        conditionEdit(() -> WorldConditionEdits.unwrap(g), true);
      ImGui.popID();
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

  private void driverNumber(
      String key,
      String label,
      double value,
      double minimum,
      double maximum,
      DoubleConsumer update) {
    settingNumber(key, label, value, minimum, maximum, update);
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
    driverNumber("inputMin", "Input minimum", d.inputMin, -1, 1, v -> d.inputMin = v);
    driverNumber("inputMax", "Input maximum", d.inputMax, -1, 1, v -> d.inputMax = v);
    driverNumber(
        "outputMin",
        "Output at minimum" + (unitOutput ? " [0,1]" : ""),
        d.outputMin,
        unitOutput ? 0 : -256,
        unitOutput ? 1 : 256,
        v -> d.outputMin = v);
    driverNumber(
        "outputMax",
        "Output at maximum" + (unitOutput ? " [0,1]" : ""),
        d.outputMax,
        unitOutput ? 0 : -256,
        unitOutput ? 1 : 256,
        v -> d.outputMax = v);
    if (unitOutput)
      ImGui.textWrapped(
          "Samples use the placement coordinates. Input clamps to the declared range; the mapped"
              + " factor multiplies the constant. Disabled factor = 1. Use ALL interpretation for a"
              + " parameter-only input.");
  }

  private void worldStructureChanged() {
    worldControlVersion++;
    noiseChanged();
    conditionErrors.clear();
    f.clear();
  }

  private void worldListValues(int index, Runnable draw) {
    ImGui.pushID("world-values:" + worldControlVersion);
    try {
      nestedSetting(Integer.toString(index), draw);
    } finally {
      ImGui.popID();
    }
  }

  private boolean worldListButton(String key, String label, boolean enabled) {
    int id = ImGui.getID(label);
    boolean clicked;
    ImGui.beginDisabled(!enabled);
    try {
      clicked = DialogLayout.button(label);
    } finally {
      ImGui.endDisabled();
    }
    worldListControls.put(
        worldSettingPath + "/" + key, new WorldListControl(id, PopupLayout.itemBounds(), enabled));
    return enabled && clicked;
  }

  private <T> WorldListEdits.Request<T> worldListActions(
      List<T> rows, int index, int limit, boolean duplicate) {
    WorldListEdits.Request<T> request = null;
    ImGui.pushID("world-actions:" + worldControlVersion);
    try {
      if (worldListButton(index + "/up", "Up", index > 0))
        request = WorldListEdits.request(rows, index, WorldListEdits.Action.UP);
      DialogLayout.sameLine("Down");
      if (worldListButton(index + "/down", "Down", index + 1 < rows.size()))
        request = WorldListEdits.request(rows, index, WorldListEdits.Action.DOWN);
      if (duplicate) {
        DialogLayout.sameLine("Duplicate");
        if (worldListButton(index + "/duplicate", "Duplicate", rows.size() < limit))
          request = WorldListEdits.request(rows, index, WorldListEdits.Action.DUPLICATE);
      }
      DialogLayout.sameLine("Remove");
      if (worldListButton(index + "/remove", "Remove", true))
        request = WorldListEdits.request(rows, index, WorldListEdits.Action.REMOVE);
      return request;
    } finally {
      ImGui.popID();
    }
  }

  private record WorldListAddition<T>(Supplier<T> create, String explanation, String identity) {
    WorldListAddition(Supplier<T> create, String explanation) {
      this(create, explanation, "");
    }
  }

  private <T> void finishWorldList(
      List<T> rows,
      WorldListEdits.Request<T> request,
      int limit,
      Class<T> type,
      String addLabel,
      Supplier<WorldListAddition<T>> addition) {
    // Delivery follows the complete row loop. A successor list/structure cannot receive an old
    // intent.
    if (WorldListEdits.apply(rows, request, limit, type)) worldStructureChanged();
    var next = addition.get();
    if (!next.explanation().isEmpty()) ImGui.textWrapped(next.explanation());
    ImGui.pushID("world-actions:" + worldControlVersion);
    ImGui.pushID("addition:" + next.identity());
    try {
      boolean add =
          worldListButton(
              "add", addLabel, rows.size() < limit && request == null && next.create() != null);
      if (add && WorldListEdits.add(rows, next.create().get(), limit)) worldStructureChanged();
    } finally {
      ImGui.popID();
      ImGui.popID();
    }
    ImGui.textDisabled(rows.size() + " / " + limit + " rows");
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
    WorldListEdits.Request<NumericDriver> request = null;
    for (int i = 0; i < drivers.size(); i++) {
      ImGui.pushID(i);
      var d = drivers.get(i);
      if (smokeWorldSettings != null || probingParameterDrivers || probingWorldNumbers)
        ImGui.setNextItemOpen(true, ImGuiCond.Always);
      if (ImGui.treeNode(
          "Driver "
              + (i + 1)
              + " : "
              + AppMetadata.settingLabel(d.destination)
              + "###world-driver")) {
        int index = i;
        worldListValues(index, () -> parameterDriverValues(settings, d, index));
        var rowRequest = worldListActions(drivers, i, 32, true);
        if (rowRequest != null) request = rowRequest;
        ImGui.treePop();
      }
      ImGui.popID();
    }
    finishWorldList(
        drivers,
        request,
        32,
        NumericDriver.class,
        "Add Parameter Driver",
        () ->
            new WorldListAddition<>(
                () -> {
                  var d = new NumericDriver();
                  d.enabled = true;
                  return d;
                },
                ""));
  }

  private void parameterDriverValues(Settings settings, NumericDriver d, int index) {
    settingControl(
        "destination",
        () -> {
          if (UiScale.beginCombo("Destination", AppMetadata.settingLabel(d.destination))) {
            for (String destination : WorldSemantics.PARAMETER_TARGETS) {
              if (ImGui.selectable(
                      AppMetadata.settingLabel(destination), d.destination.equals(destination))
                  && !d.destination.equals(destination)) {
                d.destination = destination;
                d.transformIndex =
                    WorldSemantics.parameter(destination).transform()
                        ? Math.max(0, d.transformIndex)
                        : -1;
                worldStructureChanged();
              }
            }
            ImGui.endCombo();
          }
        });
    settingControl(
        "combination",
        () ->
            f.choice(
                "Combination", d.combination, WorldSemantics.COMBINATIONS, v -> d.combination = v));
    if (WorldSemantics.PARAMETER_TARGETS.contains(d.destination)) {
      var target = WorldSemantics.parameter(d.destination);
      if (target.transform()) {
        settingInteger(
            "transformIndex",
            "Transform row (1-based)",
            d.transformIndex + 1,
            1,
            Math.max(1, settings.samplingTransforms.size()),
            v -> d.transformIndex = v <= 0 ? -1 : v - 1);
        if (d.transformIndex >= 0 && d.transformIndex < settings.samplingTransforms.size()) {
          var transform = settings.samplingTransforms.get(d.transformIndex);
          ImGui.textWrapped(
              transform.kind + (transform.enabled ? "" : " (disabled; driver is skipped)"));
        }
        ImGui.textWrapped(
            "Transform row numbers stay fixed when transforms are moved, duplicated or removed."
                + " Check targets after changing that list. Rotation uses degrees; pivots apply to"
                + " Scale and Rotate.");
      }
      ImGui.textWrapped(
          "Phase: "
              + target.phase
              + (target.explicitField
                  ? ". Explicit field required; baseline coordinates are fixed for all sampling"
                      + " rows."
                  : target.unitRange ? ". Every effective result must remain in [0,1]." : "."));
    }
    parameterDriverRow = index;
    try {
      fieldDriver(d, false);
    } finally {
      parameterDriverRow = -1;
    }
  }

  private void typeIntervals(List<TypeInterval> intervals) {
    ImGui.textWrapped(
        "Non-overlapping scalar intervals select occupied types. No match leaves the previous voxel"
            + " unchanged. Shared endpoints may belong to at most one interval.");
    WorldListEdits.Request<TypeInterval> request = null;
    for (int i = 0; i < intervals.size(); i++) {
      ImGui.pushID(i);
      var r = intervals.get(i);
      if (smokeWorldSettings != null || probingWorldNumbers)
        ImGui.setNextItemOpen(true, ImGuiCond.Always);
      if (ImGui.treeNode(
          "Interval "
              + (i + 1)
              + " : "
              + idFor(state.project().voxelTypes, r.voxelTypeUuid)
              + "###world-interval")) {
        worldListValues(
            i,
            () -> {
              settingNumber("min", "Minimum", r.min, -1, 1, v -> r.min = v);
              settingNumber("max", "Maximum", r.max, -1, 1, v -> r.max = v);
              settingControl(
                  "includeMin",
                  () -> f.bool("Include minimum", r.includeMin, v -> r.includeMin = v));
              settingControl(
                  "includeMax",
                  () -> f.bool("Include maximum", r.includeMax, v -> r.includeMax = v));
              settingControl(
                  "voxelTypeUuid",
                  () -> typePicker("Voxel type", r.voxelTypeUuid, v -> r.voxelTypeUuid = v),
                  resourcePicker::focused);
            });
        var rowRequest = worldListActions(intervals, i, 256, false);
        if (rowRequest != null) request = rowRequest;
        ImGui.treePop();
      }
      ImGui.popID();
    }
    finishWorldList(
        intervals,
        request,
        256,
        TypeInterval.class,
        "Add Type Interval",
        () -> {
          UUID type =
              state.project().voxelTypes.isEmpty()
                  ? null
                  : state.project().voxelTypes.getFirst().uuid;
          var proposal = WorldIntervalDefaults.suggest(intervals, type);
          String explanation = proposal.message();
          if (proposal.available())
            explanation += " Voxel Type: " + state.project().voxelTypes.getFirst().id;
          return new WorldListAddition<TypeInterval>(
              proposal.available() ? proposal::interval : null,
              explanation,
              proposal.message() + "/" + type);
        });
  }

  private void samplingTransforms(List<SamplingTransform> transforms) {
    ImGui.textWrapped(
        "Signed axes, then component scale/offset, then this list in order. Rotations are"
            + " right-handed degrees about the pivot. 2D fields use transformed X/Z.");
    ImGui.textWrapped(
        "Numeric drivers target row numbers. Moving, duplicating or removing a transform can change"
            + " the target or make it invalid; driver rows are not renumbered automatically.");
    WorldListEdits.Request<SamplingTransform> request = null;
    for (int i = 0; i < transforms.size(); i++) {
      ImGui.pushID(i);
      var t = transforms.get(i);
      if (smokeWorldSettings != null || probingWorldNumbers)
        ImGui.setNextItemOpen(true, ImGuiCond.Always);
      if (ImGui.treeNode("Transform " + (i + 1) + " : " + t.kind + "###world-transform")) {
        worldListValues(i, () -> samplingTransformValues(t));
        var rowRequest = worldListActions(transforms, i, 64, true);
        if (rowRequest != null) request = rowRequest;
        ImGui.treePop();
      }
      ImGui.popID();
    }
    finishWorldList(
        transforms,
        request,
        64,
        SamplingTransform.class,
        "Add Sampling Transform",
        () -> new WorldListAddition<>(SamplingTransform::new, ""));
  }

  private void samplingTransformValues(SamplingTransform t) {
    settingControl("enabled", () -> f.bool("Enabled", t.enabled, v -> t.enabled = v));
    settingControl(
        "kind",
        () ->
            f.choice(
                "Kind",
                t.kind,
                WorldSemantics.TRANSFORMS,
                v -> {
                  if (!t.kind.equals(v)) {
                    t.kind = v;
                    t.x = t.y = t.z = v.equals("SCALE") ? 1 : 0;
                    worldStructureChanged();
                  }
                }));
    double extent = t.kind.equals("ROTATE") ? 180 : t.kind.equals("SCALE") ? 8 : 1024;
    settingNumber("x", "X", t.x, -extent, extent, v -> t.x = v);
    settingNumber("y", "Y", t.y, -extent, extent, v -> t.y = v);
    settingNumber("z", "Z", t.z, -extent, extent, v -> t.z = v);
    if (!t.kind.equals("TRANSLATE")) {
      settingNumber("pivotX", "Pivot X", t.pivotX, -1024, 1024, v -> t.pivotX = v);
      settingNumber("pivotY", "Pivot Y", t.pivotY, -1024, 1024, v -> t.pivotY = v);
      settingNumber("pivotZ", "Pivot Z", t.pivotZ, -1024, 1024, v -> t.pivotZ = v);
      if (t.kind.equals("ROTATE"))
        settingControl(
            "rotationOrder",
            () ->
                f.choice(
                    "Axis order",
                    t.rotationOrder,
                    WorldSemantics.ROTATION_ORDERS,
                    v -> t.rotationOrder = v));
    }
  }

  private static final class SeedText {
    final ImString buffer;
    final ExactLongInput input = new ExactLongInput();
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
  private final Map<String, ExactLongInput.Row> seedRows = new HashMap<>();
  private boolean probeSeedTop;

  void probeSeedProblems() {
    probeSeedTop = true;
  }

  float[] probeSeedControl(String key) {
    return seedCenters.get(key);
  }

  ExactLongInput.Row probeSeedRow(String key) {
    return seedRows.get(key);
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
    ImGui.pushID("seed-recovery:" + actionOwner.version());
    if (DialogLayout.button("Restore All Seed Text")) {
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
      if (DialogLayout.button("Restore")) {
        state.seedInputs.discard(state, problem.id());
        seedTexts.remove(problem.path());
      }
      DialogLayout.sameLine("Go To Seed");
      if (DialogLayout.button("Go To Seed"))
        goToProblem(
            EditorEvents.Target.diagnostic(
                state.project().project.uuid, problem.diagnostic(state.selected)));
      ImGui.newLine();
      String label =
          problem.label()
              + ": "
              + problem.text()
              + (problem.orphan() ? " (changed gate tree)" : "");
      ImGui.textWrapped(AppMetadata.uiText(StatusBarText.clip(label, 160)));
      if (ImGui.isItemHovered()) UiScale.tooltip(problem.path() + "\n" + label);
      ImGui.popID();
    }
    ImGui.popID();
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
    SeedText owned = input;
    String metadataId =
        worldSettingPath == null
            ? null
            : WorldControlMetadata.setting(
                worldSettingPath, worldSettingPath.equals("seedOffset") ? "" : "seedOffset");
    ImGui.textWrapped(label);
    var row =
        owned.input.draw(
            "###seed-text-" + key,
            Math.max(1, ImGui.getContentRegionAvailX()),
            owned.buffer,
            owned.buffer.get(),
            value,
            !owned.invalid,
            text -> {
              try {
                var parsed = state.seedInputs.edit(state, key, label, text);
                owned.invalid = parsed.isEmpty();
                if (parsed.isPresent()) {
                  set.accept(parsed.getAsLong());
                  owned.model = parsed.getAsLong();
                }
                noiseChanged();
              } catch (RuntimeException invalid) {
                owned.buffer.set(
                    Objects.requireNonNullElse(state.seedInputs.text(key), Long.toString(value)));
                owned.invalid = state.seedInputs.text(key) != null;
                state.status = invalid.getMessage();
              }
            },
            () -> {
              if (!searchBlocked() && propertyFocus.matches(null, key))
                ImGui.setKeyboardFocusHere();
            },
            metadataId == null
                ? null
                : nativeId -> {
                  var control = state.metadata.control(metadataId);
                  if (control != null) {
                    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
                      UiScale.tooltip(
                          WorldControlMetadata.integerTooltip(
                              control.tooltip, owned.buffer.get(), owned.invalid));
                    contextHelp.item(
                        metadataId, NumericGuidance.identity(metadataId, nativeId), true);
                  }
                });
    seedRows.put(key, row);
    seedCenters.put(
        key,
        new float[] {
          row.input().x() + Math.min(80, row.input().width() * .25f),
          row.input().y() + row.input().height() / 2
        });
  }
}
