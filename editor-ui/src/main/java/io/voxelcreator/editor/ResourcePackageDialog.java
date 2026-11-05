package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Production package path/review modal, shared by Desktop and deferred native workflows. */
final class ResourcePackageDialog implements AutoCloseable {
  private final EditorState state;
  private final ResourcePanel resources;
  private final ResourceImportPanel imports;
  private final ResourcePackageFiles files;
  private final BooleanSupplier blocked;
  final DialogLayout layout;
  private final SettingsPageChrome exportChrome = new SettingsPageChrome(4, 3);
  private final SettingsPageChrome importChrome = new SettingsPageChrome(2);
  private final ImString path = new ImString(4096);
  private final Map<String, float[]> controls = new LinkedHashMap<>();
  private String action = "", page = "Path", error = "";
  private boolean pending, prompt, focusPath, revealPage, exportDrawn;
  private ExportEditSession exportOwner;
  private final NavigationDecisionScope delivery = new NavigationDecisionScope();
  private long generation, shownOpening = -1;
  private int drawnFrame = Integer.MIN_VALUE;
  private boolean working, closed, importObserved, exportObserved;
  private final ResourceImportLoader loader;
  private final ResourceExportWriter writer;

  enum Command {
    CANCEL,
    SUBMIT,
    RELOAD,
    REMOVE,
    CHOICES,
    INCLUDED,
    LOCATION
  }

  record Decision(
      NavigationDecisionScope.Ticket ticket,
      String action,
      String location,
      ExportEditSession owner,
      ResourceExportSession.Preview preview,
      Set<UUID> selected,
      ResourceReviewContext context) {}

  static ResourcePackageDialog background(
      EditorState state,
      ResourcePanel resources,
      ResourceImportPanel imports,
      BooleanSupplier blocked) {
    var files = new ResourcePackageFiles();
    return new ResourcePackageDialog(
        state,
        resources,
        imports,
        blocked,
        files,
        state.background,
        state.uiQueue::offer,
        (source, destination) -> ImportEditSession.prepare(destination, files.read(source)));
  }

  ResourcePackageDialog(
      EditorState state,
      ResourcePanel resources,
      ResourceImportPanel imports,
      BooleanSupplier blocked) {
    this(state, resources, imports, blocked, new ResourcePackageFiles());
  }

  ResourcePackageDialog(
      EditorState state,
      ResourcePanel resources,
      ResourceImportPanel imports,
      BooleanSupplier blocked,
      ResourcePackageFiles files) {
    this(state, resources, imports, blocked, files, null, null, null);
  }

  ResourcePackageDialog(
      EditorState state,
      ResourcePanel resources,
      ResourceImportPanel imports,
      BooleanSupplier blocked,
      ResourcePackageFiles files,
      Executor executor,
      Consumer<Runnable> ui,
      ResourceImportLoader.Preparation preparation) {
    this.state = Objects.requireNonNull(state);
    this.resources = Objects.requireNonNull(resources);
    this.imports = Objects.requireNonNull(imports);
    this.blocked = Objects.requireNonNull(blocked);
    this.files = Objects.requireNonNull(files);
    layout = new DialogLayout(state, "Resources", 780, 620);
    loader =
        executor == null
            ? null
            : new ResourceImportLoader(
                state, executor, ui, preparation, imports::openPrepared, blocked);
    writer =
        executor == null
            ? null
            : new ResourceExportWriter(state, executor, ui, files::prepare, blocked);
  }

  boolean open(String requested) {
    if (!Set.of("Import", "Export").contains(requested))
      throw new IllegalArgumentException("Unknown package action");
    if (closed || pending || working || imports.pending() || resources.exportView().busy()) {
      state.status =
          "Finish the private editor or pending project action before opening a resource package";
      return false;
    }
    long opening = ++generation;
    var context = ResourceReviewContext.capture(state);
    try {
      boolean unavailable = blocked.getAsBoolean();
      if (generation != opening
          || closed
          || pending
          || working
          || imports.pending()
          || resources.exportView().busy()
          || !context.current(state)) return false;
      if (unavailable) {
        state.status =
            "Finish the private editor or pending project action before opening a resource package";
        return false;
      }
    } catch (RuntimeException failure) {
      report(requested, "FILE_PACKAGE_GATE_FAILED", failure);
      return false;
    }
    if (requested.equals("Export")) resources.beginExport();
    exportOwner = requested.equals("Export") ? resources.exportView().session() : null;
    action = requested;
    page = requested.equals("Export") ? "Choices" : "Path";
    path.set(ResourcePackageFiles.suggestedLocation(state.document.path()).toString());
    error = "";
    delivery.open();
    pending = prompt = revealPage = true;
    focusPath = page.equals("Path");
    exportDrawn = false;
    return true;
  }

  boolean pending() {
    return pending;
  }

  String action() {
    return action;
  }

  String page() {
    return page;
  }

  String error() {
    return error;
  }

  String pathText() {
    return path.get();
  }

  boolean exportDrawn() {
    return exportDrawn;
  }

  SettingsPageChrome chrome() {
    return action.equals("Export") ? exportChrome : importChrome;
  }

  void cancel() {
    if (working
        || exportOwner != null && exportOwner.busy()
        || writer != null && writer.publishing()) return;
    generation++;
    if (loader != null) loader.cancel();
    if (writer != null) writer.cancel();
    importObserved = exportObserved = false;
    delivery.close();
    if (pending && action.equals("Export")) cancelOwnedExport();
    exportOwner = null;
    pending = prompt = false;
  }

  void draw() {
    pollImport();
    pollExport();
    int frame = ImGui.getFrameCount();
    if (drawnFrame == frame) return;
    drawnFrame = frame;
    controls.clear();
    if (!pending) {
      closePopup();
      return;
    }
    var opening = capture(frame);
    var importWork = importRequestToken();
    boolean unavailable;
    try {
      unavailable = blocked.getAsBoolean();
    } catch (RuntimeException failure) {
      failed(opening, "FILE_PACKAGE_GATE_FAILED", failure);
      unavailable = true;
    }
    if (!owns(opening)) return;
    if (prompt || shownOpening != delivery.version()) {
      closePopup();
      layout.open("File Location");
      prompt = false;
      shownOpening = delivery.version();
    }
    if (!layout.begin("File Location", ImGuiWindowFlags.NoSavedSettings)) return;
    boolean export = action.equals("Export");
    Command command = null;
    Decision decision = null;
    try {
      ImGui.pushID("resource-package:" + opening.ticket().version());
      try {
        var view = resources.exportView();
        boolean owned = !export || view.session() == exportOwner;
        var chrome = chrome();
        chrome.begin("package-file-body");
        try {
          if (revealPage) {
            ImGui.setScrollY(0);
            revealPage = false;
          }
          if (!export || page.equals("Path")) {
            ImGui.textWrapped(action + " file path");
            ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
            if (focusPath && !packageRunning()) ImGui.setKeyboardFocusHere();
            ImGui.beginDisabled(packageRunning());
            ImGui.inputText("##package-path", path);
            ImGui.endDisabled();
            remember("Path");
            if (focusPath && !packageRunning()) {
              ImGui.setScrollHereY(.25f);
              if (ImGui.isItemActive() || ImGui.isItemFocused()) focusPath = false;
            }
            if (importRunning()) ImGui.textWrapped(loader.view().message());
            if (exportRunning()) ImGui.textWrapped(writer.view().message());
            if (!error.isEmpty()) ImGui.textWrapped(StatusBarText.clip(error, 4096));
            if (unavailable)
              ImGui.textWrapped("Finish the other private editor or project action, or Cancel.");
            if (export && owned) view.drawPage("Issues");
            else if (export)
              ImGui.textWrapped("The export workspace was replaced. Cancel and reopen Export.");
            else
              ImGui.textWrapped(
                  "Import reads a versioned resource package, then opens a separate COPY/REUSE/SKIP"
                      + " review. It does not replace the current project.");
          } else if (!owned) {
            ImGui.textWrapped("The export workspace was replaced. Cancel and reopen Export.");
          } else {
            view.drawPage(page);
            exportDrawn |= view.listBounds() != null;
          }
        } finally {
          chrome.end();
        }
        boolean submit =
            button(
                chrome,
                "submit",
                action,
                !packageRunning() && !unavailable && owned && (!export || resources.canExport()));
        boolean cancel = button(chrome, "cancel", "Cancel", true);
        boolean reload = false, remove = false, choices = false, included = false, location = false;
        if (export) {
          reload =
              button(
                  chrome,
                  "reload",
                  view.reloadLabel(),
                  !packageRunning() && !unavailable && owned && view.canReload());
          remove =
              button(
                  chrome,
                  "remove-unavailable",
                  "Remove Missing",
                  !packageRunning() && !unavailable && owned && view.canRemoveUnavailable());
          choices = button(chrome, "choices", "Choices", !packageRunning());
          included = button(chrome, "included", "Included", !packageRunning());
          location = button(chrome, "location", "Path / Issues", !packageRunning());
        }
        // Escape owns the whole decision frame even when a native release also submits.
        if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) command = Command.CANCEL;
        else if (reload) command = Command.RELOAD;
        else if (remove) command = Command.REMOVE;
        else if (submit) command = Command.SUBMIT;
        else if (choices) command = Command.CHOICES;
        else if (included) command = Command.INCLUDED;
        else if (location) command = Command.LOCATION;
        decision = capture(frame);
      } finally {
        ImGui.popID();
      }
    } finally {
      ImGui.endPopup();
    }
    if (command != null) dispatch(decision, command, frame);
    acceptImport(opening, importWork);
    if (delivery.version() == opening.ticket().version() && !pending) closePopup();
  }

  Decision capture(int frame) {
    return new Decision(
        delivery.capture(frame),
        action,
        path.get(),
        exportOwner,
        exportOwner == null ? null : exportOwner.preview(),
        exportOwner == null ? Set.of() : exportOwner.selected(),
        ResourceReviewContext.capture(state));
  }

  private boolean owns(Decision decision) {
    return decision != null
        && delivery.owns(decision.ticket())
        && pending
        && action.equals(decision.action())
        && exportOwner == decision.owner();
  }

  private boolean current(Decision decision) {
    return owns(decision)
        && decision.context().current(state)
        && path.get().equals(decision.location())
        && (!decision.action().equals("Export")
            || resources.exportView().session() == decision.owner()
                && decision.owner().preview() == decision.preview()
                && resources.exportSelection.equals(decision.selected()));
  }

  void dispatch(Decision decision, Command command, int frame) {
    if (working || !owns(decision) || !delivery.consume(decision.ticket(), frame)) return;
    if (command == Command.CANCEL) {
      cancel();
      return;
    }
    if (packageRunning()) return;
    if (command == Command.CHOICES) {
      show("Choices");
      return;
    }
    if (command == Command.INCLUDED) {
      show("Included");
      return;
    }
    if (command == Command.LOCATION) {
      show("Path");
      return;
    }
    try {
      boolean unavailable = blocked.getAsBoolean();
      if (unavailable || !current(decision)) return;
      var view = resources.exportView();
      if (command == Command.RELOAD) {
        view.reload();
        error = "";
        show("Included");
        return;
      }
      if (command == Command.REMOVE) {
        view.removeUnavailable();
        return;
      }
      if (command != Command.SUBMIT) return;
      if (decision.action().equals("Import") && loader != null) {
        error = "";
        importObserved = true;
        loader.start(decision.location());
        pollImport();
        return;
      }
      if (decision.action().equals("Export") && writer != null) {
        error = "";
        exportObserved = true;
        writer.start(
            decision.location(), decision.owner(), decision.preview(), () -> current(decision));
        show("Path");
        pollExport();
        return;
      }
      working = true;
      try {
        var destination = ResourcePackageFiles.location(decision.location());
        if (decision.action().equals("Export")) resources.writeExport(destination, files);
        else if (!imports.open(files.read(destination)))
          throw new IOException("Import is blocked or the package was rejected");
      } finally {
        working = false;
      }
      // Retire only this opening before any completion notification.
      if (owns(decision)) {
        boolean exported = decision.action().equals("Export");
        cancel();
        if (exported) state.notify("Information", "Resource package exported");
      }
    } catch (Exception failure) {
      failed(decision, "FILE_" + decision.action().toUpperCase(Locale.ROOT) + "_FAILED", failure);
    }
  }

  boolean importRunning() {
    return loader != null && loader.view().phase() == ResourceImportLoader.Phase.RUNNING;
  }

  Object importRequestToken() {
    return loader == null ? null : loader.requestToken();
  }

  void acceptImport(Decision shown, Object request) {
    if (loader == null || !importObserved || !owns(shown)
        || !shown.action().equals("Import") || loader.requestToken() != request) return;
    loader.acceptPrepared(path.get(), () -> current(shown));
    if (owns(shown)) pollImport();
  }

  boolean exportRunning() {
    return writer != null && writer.view().phase() == ResourceExportWriter.Phase.RUNNING;
  }

  private boolean packageRunning() {
    return importRunning() || exportRunning();
  }

  void pollExport() {
    if (writer == null || !exportObserved || !pending) return;
    var view = writer.view();
    if (view.phase() == ResourceExportWriter.Phase.RUNNING) return;
    exportObserved = false;
    if (view.phase() == ResourceExportWriter.Phase.READY) {
      cancel();
      state.notify("Information", "Resource package exported");
    } else {
      error = view.message();
      show("Path");
    }
  }

  void pollImport() {
    if (loader == null || !importObserved || !pending) return;
    var view = loader.view();
    if (view.phase() == ResourceImportLoader.Phase.RUNNING) return;
    importObserved = false;
    if (view.phase() == ResourceImportLoader.Phase.READY) cancel();
    else {
      error = view.message();
      show("Path");
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (loader != null) loader.close();
    if (writer != null) writer.close();
    cancel();
  }

  private void failed(Decision decision, String code, Exception failure) {
    String detail = report(decision.action(), code, failure);
    if (owns(decision)) {
      error = detail;
      show("Path");
    }
  }

  private String report(String requested, String code, Exception failure) {
    String detail =
        StatusBarText.bounded(
            requested
                + " failed: "
                + Objects.requireNonNullElse(failure.getMessage(), failure.toString()),
            1024);
    state.error(code, EditorEvents.Subsystem.IMPORT_EXPORT, detail);
    return detail;
  }

  private void closePopup() {
    if (layout.begin("File Location", ImGuiWindowFlags.NoSavedSettings)) {
      try {
        ImGui.closeCurrentPopup();
      } finally {
        ImGui.endPopup();
      }
    }
  }

  private void cancelOwnedExport() {
    if (resources.exportView().session() == exportOwner) resources.cancelExport();
    else if (exportOwner != null) exportOwner.cancel();
  }

  private void show(String value) {
    page = value;
    revealPage = true;
    focusPath = value.equals("Path");
  }

  private boolean button(SettingsPageChrome chrome, String key, String label, boolean enabled) {
    ImGui.beginDisabled(!enabled);
    boolean pressed = chrome.identifiedAction(key, label);
    ImGui.endDisabled();
    remember(key);
    return enabled && pressed;
  }

  private void remember(String key) {
    var r = PopupLayout.itemBounds();
    controls.put(key, new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
  }

  float[] control(String key) {
    return controls.get(key);
  }
}
