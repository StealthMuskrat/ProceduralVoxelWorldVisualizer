package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** Manual results, cancellable request and explicit verified-link actions. */
final class UpdateCheckPanel implements AutoCloseable {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(730, 350);
  final SettingsPageChrome chrome = new SettingsPageChrome(3, 2);
  final SettingsActionScope actionScope = new SettingsActionScope();
  private final UpdateCheckSession session;
  private final String installed;
  private final Runnable configure;
  private final UpdateCheckSession.Opener opener;
  private final Consumer<String> clipboard, errors;
  private String message = "";
  private Object messageToken;
  private UpdateCheckSession.Result messageResult;
  private boolean probing, revealFeedback;
  private long shownRequest = -1;
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
    clearFeedback();
    session.start();
  }

  void draw(ImBoolean open) {
    if (probing) controls.clear();
    var view = session.view();
    if (!open.get()) {
      session.cancel();
      clearFeedback();
      actionScope.release();
      return;
    }
    windowLayout.prepare();
    boolean visible = ImGui.begin("Check for Updates", open);
    UiScale.window(session.preferences(), "Check for Updates");
    windowLayout.observe();
    try {
      if (!visible || !open.get()) {
        actionScope.release();
        return;
      }
      long request =
          actionScope.synchronize(session.requestToken(), owner(view), view.phase().ordinal());
      var reviewed = view.result();
      if (messageToken != session.requestToken() || messageResult != reviewed) {
        clearFeedback();
      }
      chrome.begin("update-check-body");
      if (shownRequest != request) {
        ImGui.setScrollY(0);
        shownRequest = request;
      }
      ImGui.textWrapped("Installed application: " + installed);
      ImGui.textWrapped(view.message());
      if (reviewed != null) {
        ImGui.separator();
        ImGui.textWrapped("Release information: " + reviewed.release().releaseUrl());
      }
      if (!message.isEmpty()) ImGui.textWrapped(message);
      if (revealFeedback) {
        ImGui.setScrollHereY(1);
        revealFeedback = false;
      }
      ImGui.textWrapped(
          "Checks are manual. Only explicit link actions open your browser or copy a URL.");
      chrome.end();
      boolean running = view.phase() == UpdateCheckSession.Phase.RUNNING;
      boolean closed = view.phase() == UpdateCheckSession.Phase.CLOSED;
      ImGui.pushID("update-check-request:" + request);
      ImGui.beginDisabled(running || closed);
      boolean check = chrome.identifiedAction("check", "Check Again");
      control("check");
      ImGui.endDisabled();
      ImGui.beginDisabled(!running);
      boolean cancel = chrome.identifiedAction("cancel", "Cancel Check");
      control("cancel");
      ImGui.endDisabled();
      ImGui.beginDisabled(closed);
      boolean settings = chrome.identifiedAction("configure", "Configure Update Trust");
      control("configure");
      ImGui.endDisabled();
      ImGui.beginDisabled(reviewed == null || closed);
      boolean browse = chrome.identifiedAction("open", "Open Release Information");
      control("open");
      boolean copy = chrome.identifiedAction("copy", "Copy Release Link");
      control("copy");
      ImGui.endDisabled();
      ImGui.popID();
      var current = session.view();
      if (!actionScope.current(session.requestToken(), owner(current), current.phase().ordinal()))
        return;
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        if (running) cancel();
      } else if (cancel && running) cancel();
      else if (settings && !closed) configure.run();
      else if (check && !running && !closed) start();
      else if (browse && reviewed != null) action(() -> session.open(reviewed, opener));
      else if (copy && reviewed != null) action(() -> session.copy(reviewed, clipboard));
    } finally {
      ImGui.end();
      if (!open.get()) {
        session.cancel();
        clearFeedback();
        actionScope.release();
      }
    }
  }

  private Object owner(UpdateCheckSession.View view) {
    return view.result() == null ? session : view.result();
  }

  private void cancel() {
    session.cancel();
    clearFeedback();
  }

  private void clearFeedback() {
    message = "";
    revealFeedback = false;
    messageToken = null;
    messageResult = null;
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }

  private void action(Action action) {
    var reviewed = session.view().result();
    var token = session.requestToken();
    try {
      action.run();
      feedback(token, reviewed, "Release link action completed");
    } catch (Exception failure) {
      String detail = "Release link action failed: " + UpdateCheckSession.describe(failure);
      errors.accept(detail);
      feedback(token, reviewed, detail);
    }
  }

  private void feedback(Object token, UpdateCheckSession.Result reviewed, String text) {
    var current = session.view();
    if (session.requestToken() != token || current.result() != reviewed) return;
    messageToken = token;
    messageResult = reviewed;
    message = text;
    revealFeedback = true;
  }

  String probeMessage() {
    return message;
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
    clearFeedback();
    actionScope.release();
  }
}
