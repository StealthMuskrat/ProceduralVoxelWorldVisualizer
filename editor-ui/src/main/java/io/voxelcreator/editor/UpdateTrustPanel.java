package io.voxelcreator.editor;

import imgui.*;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Private manual-update settings retained across ordinary Settings navigation and save failures.
 */
final class UpdateTrustPanel {
  private final UserConfig user;
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
    ensure();
    ImGui.separator();
    ImGui.textWrapped(
        "Manual update checks use applied values only. This private draft is retained until Apply"
            + " or Revert; editing it makes no network requests.");
    control("blur");
    if (ImGui.inputText("Manual update endpoint", endpoint))
      act(() -> session.editEndpoint(endpoint.get()));
    control("endpoint");
    if (ImGui.inputTextMultiline("Approved HTTPS origins (one per line)", origins, 500, 100))
      act(() -> session.editOrigins(origins.get()));
    control("origins");
    String displayedProblem = "";
    try {
      session.requireDisplayed(displayed());
    } catch (RuntimeException mismatch) {
      displayedProblem = mismatch.getMessage();
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
    ImGui.beginDisabled(!applyEnabled);
    if (ImGui.button("Apply Update Trust"))
      act(
          () -> {
            session.apply(user, file, displayed());
            sync();
            message = "Update trust saved";
          });
    control("apply");
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Update Trust"))
      act(
          () -> {
            session.revert(user);
            sync();
            message = "Active update settings reloaded";
          });
    control("revert");
    if (session.changed()) ImGui.textDisabled("Pending update settings are private");
    if (!message.isEmpty()) ImGui.textWrapped(message);
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
    if (probing)
      controls.put(
          name,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
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
