package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** Manual results, cancellable request and explicit verified-link actions. */
final class UpdateCheckPanel implements AutoCloseable {
  private final UpdateCheckSession session;
  private final String installed;
  private final Runnable configure;
  private final UpdateCheckSession.Opener opener;
  private final Consumer<String> clipboard, errors;
  private String message = "";
  private boolean probing;
  private final Map<String, float[]> controls = new HashMap<>();

  UpdateCheckPanel(
      UpdateCheckSession session,
      String installed,
      Runnable configure,
      UpdateCheckSession.Opener opener,
      Consumer<String> clipboard,
      Consumer<String> errors) {
    this.session = Objects.requireNonNull(session);
    this.installed = installed;
    this.configure = Objects.requireNonNull(configure);
    this.opener = Objects.requireNonNull(opener);
    this.clipboard = Objects.requireNonNull(clipboard);
    this.errors = Objects.requireNonNull(errors);
  }

  void start() {
    message = "";
    session.start();
  }

  void draw(ImBoolean open) {
    session.refresh();
    if (!open.get()) {
      session.cancel();
      return;
    }
    ImGui.setNextWindowSize(730, 350, ImGuiCond.FirstUseEver);
    boolean visible = ImGui.begin("Check for Updates", open);
    try {
      if (!visible || !open.get()) return;
      var view = session.view();
      ImGui.text("Installed application: " + installed);
      ImGui.textWrapped(view.message());
      boolean running = view.phase() == UpdateCheckSession.Phase.RUNNING;
      ImGui.beginDisabled(running || view.phase() == UpdateCheckSession.Phase.CLOSED);
      if (ImGui.button("Check Again")) start();
      control("check");
      ImGui.endDisabled();
      ImGui.sameLine();
      ImGui.beginDisabled(!running);
      if (ImGui.button("Cancel Check")) {
        session.cancel();
        message = "";
      }
      control("cancel");
      ImGui.endDisabled();
      ImGui.sameLine();
      if (ImGui.button("Configure Update Trust")) configure.run();
      control("configure");
      var reviewed = session.view().result();
      if (reviewed != null) {
        ImGui.separator();
        ImGui.textWrapped("Release information: " + reviewed.release().releaseUrl());
        if (ImGui.button("Open Release Information")) action(() -> session.open(reviewed, opener));
        control("open");
        ImGui.sameLine();
        if (ImGui.button("Copy Release Link")) action(() -> session.copy(reviewed, clipboard));
        control("copy");
      }
      if (!message.isEmpty()) ImGui.textWrapped(message);
      ImGui.textDisabled(
          "Checks are manual. Only explicit link actions open your browser or copy a URL.");
    } finally {
      ImGui.end();
      if (!open.get()) session.cancel();
    }
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }

  private void action(Action action) {
    try {
      action.run();
      message = "Release link action completed";
    } catch (Exception failure) {
      message = "Release link action failed: " + UpdateCheckSession.describe(failure);
      errors.accept(message);
    }
  }

  static void browse(URI uri) throws Exception {
    if (!java.awt.Desktop.isDesktopSupported()
        || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
      throw new IllegalStateException(
          "Browser integration is unavailable; Copy Release Link to use another browser");
    java.awt.Desktop.getDesktop().browse(uri);
  }

  private void control(String key) {
    if (probing)
      controls.put(
          key,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
  }

  void probeControls() {
    probing = true;
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }

  @Override
  public void close() {
    session.close();
  }
}
