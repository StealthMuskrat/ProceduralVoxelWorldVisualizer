package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Private manual-update settings retained across ordinary Settings navigation and save failures.
 */
final class UpdateTrustPanel {
  private final UserConfig user;
  final PreferenceActionScope actionScope = new PreferenceActionScope();
  private final PreferenceSaveAction saveActions = new PreferenceSaveAction();
  private int saveId;
  private boolean revealSave;
  final SettingsPageChrome layout = new SettingsPageChrome(3);
  private String reveal;
  private boolean originsNext;
  private final Map<String, Integer> fieldIds = new HashMap<>();
  private final Map<String, DialogBounds.Rect> bounds = new HashMap<>();
  private final Path file;
  private final Consumer<String> errors;
  private UpdateTrustSession session;
  private ImString endpoint, origins;
  private String message = "";
  private boolean probing, applyEnabled;
  private final Map<String, float[]> controls = new HashMap<>();

  UpdateTrustPanel(UserConfig user, Path file, Consumer<String> errors) {
    this.user = Objects.requireNonNull(user);
    this.file = Objects.requireNonNull(file);
    this.errors = Objects.requireNonNull(errors);
  }

  private void ensure() {
    if (session == null) {
      session = new UpdateTrustSession(user);
      sync();
    }
  }

  private void sync() {
    actionScope.resetDraft();
    endpoint =
        new ImString(
            session.text().endpoint(),
            Math.addExact(Math.multiplyExact(4, session.endpointLimit()), 1));
    origins =
        new ImString(
            session.text().origins(),
            Math.addExact(Math.multiplyExact(4, session.originsLimit()), 1));
  }

  void draw() {
    draw(null);
  }

  void draw(Runnable savePreferences) {
    ensure();
    long request = actionScope.synchronize(activeValues());
    long saveRequest = saveActions.synchronize(actionScope, savePreferences != null);
    boolean save = false;
    saveId = 0;
    if (probing) {
      controls.clear();
      fieldIds.clear();
      bounds.clear();
    }
    layout.begin("update-trust-body");
    ImGui.textWrapped(
        "Manual update checks use applied values only. This private draft is retained until Apply"
            + " or Revert; editing it makes no network requests.");
    control("blur");
    ImGui.textWrapped("Manual update endpoint");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    if (probing) fieldIds.put("endpoint", ImGui.getID("##manual-update-endpoint"));
    boolean focus = "endpoint".equals(reveal);
    if (focus) ImGui.setKeyboardFocusHere();
    if (ImGui.inputText("##manual-update-endpoint", endpoint))
      act(() -> session.editEndpoint(endpoint.get()));
    control("endpoint");
    if (focus) {
      ImGui.setScrollHereY(.2f);
      reveal = null;
    }
    ImGui.textWrapped("Approved HTTPS origins (one per line)");
    if (probing) fieldIds.put("origins", ImGui.getID("##manual-update-origins"));
    focus = "origins".equals(reveal);
    if (focus) ImGui.setKeyboardFocusHere();
    if (ImGui.inputTextMultiline(
        "##manual-update-origins",
        origins,
        Math.max(1, ImGui.getContentRegionAvailX()),
        SettingsPageLayout.multiline(layout.body().height(), ImGui.getTextLineHeightWithSpacing())))
      act(() -> session.editOrigins(origins.get()));
    control("origins");
    if (focus) {
      ImGui.setScrollHereY(.5f);
      reveal = null;
    }
    String displayedProblem = "";
    try {
      session.requireDisplayed(displayed());
    } catch (RuntimeException mismatch) {
      displayedProblem = Objects.requireNonNullElse(mismatch.getMessage(), mismatch.toString());
    }
    var feedback = session.feedback();
    if (!feedback.problem().isEmpty()) ImGui.textWrapped("! " + feedback.problem());
    if (!displayedProblem.isEmpty()) ImGui.textWrapped("! " + displayedProblem);
    boolean current = session.current(user);
    if (!current)
      ImGui.textWrapped(
          "Active update settings changed. Revert reloads them; this private draft has been"
              + " retained.");
    applyEnabled = current && feedback.value() != null && displayedProblem.isEmpty();
    if (session.changed()) ImGui.textWrapped("Pending update settings are private");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    if (savePreferences != null) {
      ImGui.pushID("update-trust-save:" + saveActions.nativeId() + ":" + saveRequest);
      saveId = ImGui.getID("Save Preferences");
      save = DialogLayout.button("Save Preferences");
      control("save-preferences");
      if (revealSave) {
        ImGui.setScrollHereY(.5f);
        revealSave = false;
      }
      ImGui.popID();
    }
    layout.end();
    ImGui.pushID("update-trust-request:" + actionScope.nativeId() + ":" + request);
    ImGui.beginDisabled(!applyEnabled);
    boolean apply = layout.identifiedAction("apply", "Apply Update Trust");
    control("apply");
    ImGui.endDisabled();
    boolean revert = layout.identifiedAction("revert", "Revert Update Trust");
    control("revert");
    boolean fields = layout.identifiedAction("fields", "Show Update Fields");
    control("fields");
    ImGui.popID();
    if (!actionScope.current(activeValues()) || ImGui.isKeyPressed(ImGuiKey.Escape, false)) return;
    if (revert)
      act(
          () -> {
            session.revert(user);
            sync();
            message = "Active update settings reloaded";
          });
    else if (apply && applyEnabled)
      act(
          () -> {
            session.apply(user, file, displayed());
            sync();
            message = "Update trust saved";
          });
    else if (fields) {
      reveal = originsNext ? "origins" : "endpoint";
      originsNext = !originsNext;
    } else if (save && savePreferences != null && saveActions.current(actionScope, true))
      savePreferences.run();
  }

  private record ActiveValues(String endpoint, List<String> origins) {
    ActiveValues {
      if (origins != null) origins = Collections.unmodifiableList(new ArrayList<>(origins));
    }
  }

  private ActiveValues activeValues() {
    return new ActiveValues(user.performance.updateEndpoint, user.performance.trustedOrigins);
  }

  void suspendActions() {
    actionScope.resetDraft();
    saveActions.release();
  }

  void probeRevealSave() {
    revealSave = true;
  }

  int probeSaveId() {
    return saveId;
  }

  void probeEdit(String target, String approved) {
    ensure();
    session.editEndpoint(target);
    session.editOrigins(approved);
    endpoint.set(target);
    origins.set(approved);
  }

  void probeRevert() {
    ensure();
    session.revert(user);
    sync();
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
  }

  Map<String, DialogBounds.Rect> bounds() {
    return Map.copyOf(bounds);
  }

  private UpdateTrustSession.Text displayed() {
    return new UpdateTrustSession.Text(endpoint.get(), origins.get());
  }

  private void act(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException error) {
      String detail = error.getMessage();
      if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
      if (error.getCause() != null)
        detail +=
            ": "
                + Objects.requireNonNullElse(
                    error.getCause().getMessage(), error.getCause().getClass().getSimpleName());
      message = "! " + (detail.length() > 1024 ? detail.substring(0, 1023) + "…" : detail);
      errors.accept(message);
    }
  }

  private void control(String name) {
    if (probing) {
      bounds.put(name, PopupLayout.itemBounds());
      controls.put(
          name,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
    }
  }

  void probeControls() {
    probing = true;
  }

  float[] probeControl(String name) {
    return controls.get(name);
  }

  UpdateTrustSession.Text probeText() {
    return session.text();
  }

  boolean probeApplyEnabled() {
    return applyEnabled;
  }

  long validations() {
    return session.validations();
  }
}
