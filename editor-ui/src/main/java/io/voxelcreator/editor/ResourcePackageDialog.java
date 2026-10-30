package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Production package path/review modal, shared by Desktop and deferred native workflows. */
final class ResourcePackageDialog {
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
    this.state = Objects.requireNonNull(state);
    this.resources = Objects.requireNonNull(resources);
    this.imports = Objects.requireNonNull(imports);
    this.blocked = Objects.requireNonNull(blocked);
    this.files = Objects.requireNonNull(files);
    layout = new DialogLayout(state, "Resources", 780, 620);
  }

  boolean open(String requested) {
    if (!Set.of("Import", "Export").contains(requested))
      throw new IllegalArgumentException("Unknown package action");
    if (pending || imports.pending() || blocked.getAsBoolean()) {
      state.status =
          "Finish the private editor or pending project action before opening a resource package";
      return false;
    }
    if (requested.equals("Export")) resources.beginExport();
    exportOwner = requested.equals("Export") ? resources.exportView().session() : null;
    action = requested;
    page = requested.equals("Export") ? "Choices" : "Path";
    path.set(ResourcePackageFiles.suggestedLocation(state.document.path()).toString());
    error = "";
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
    if (pending && action.equals("Export")) {
      if (resources.exportView().session() == exportOwner) resources.cancelExport();
      else if (exportOwner != null) exportOwner.cancel();
    }
    exportOwner = null;
    pending = prompt = false;
  }

  void draw() {
    controls.clear();
    if (prompt) {
      layout.open("File Location");
      prompt = false;
    }
    if (!layout.begin("File Location", ImGuiWindowFlags.NoSavedSettings)) return;
    if (!pending) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      return;
    }
    boolean export = action.equals("Export"), unavailable = blocked.getAsBoolean();
    var view = resources.exportView();
    boolean owned = !export || view.session() == exportOwner;
    var chrome = chrome();
    chrome.begin("package-file-body");
    if (revealPage) {
      ImGui.setScrollY(0);
      revealPage = false;
    }
    if (!export || page.equals("Path")) {
      ImGui.textWrapped(action + " file path");
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      if (focusPath) ImGui.setKeyboardFocusHere();
      ImGui.inputText("##package-path", path);
      remember("Path");
      if (focusPath) {
        ImGui.setScrollHereY(.25f);
        if (ImGui.isItemActive() || ImGui.isItemFocused()) focusPath = false;
      }
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
    chrome.end();
    boolean submit =
        button(
            chrome, "submit", action, !unavailable && owned && (!export || resources.canExport()));
    boolean cancel = button(chrome, "cancel", "Cancel", true);
    boolean reload = false, remove = false, choices = false, included = false, location = false;
    if (export) {
      reload = button(chrome, "reload", "Reload", !unavailable && owned && view.canReload());
      remove =
          button(
              chrome,
              "remove-unavailable",
              "Remove Missing",
              !unavailable && owned && view.current() && view.unavailable() > 0);
      choices = button(chrome, "choices", "Choices", true);
      included = button(chrome, "included", "Included", true);
      location = button(chrome, "location", "Path / Issues", true);
    }
    // A native release can coincide with Escape: cancellation owns this entire decision frame.
    if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      cancel();
      ImGui.closeCurrentPopup();
    } else if (reload) {
      try {
        view.reload();
        error = "";
        show("Included");
      } catch (RuntimeException failure) {
        failed(failure);
      }
    } else if (remove) view.removeUnavailable();
    else if (submit && !blocked.getAsBoolean()) {
      try {
        var destination = ResourcePackageFiles.location(path.get());
        if (export) {
          resources.writeExport(destination, files);
          resources.cancelExport();
          state.notify("Information", "Resource package exported");
        } else if (!imports.open(files.read(destination)))
          throw new IOException("Import is blocked or the package was rejected");
        pending = false;
        exportOwner = null;
        ImGui.closeCurrentPopup();
      } catch (Exception failure) {
        failed(failure);
      }
    } else if (choices) show("Choices");
    else if (included) show("Included");
    else if (location) show("Path");
    ImGui.endPopup();
  }

  private void failed(Exception failure) {
    error =
        action + " failed: " + Objects.requireNonNullElse(failure.getMessage(), failure.toString());
    state.error(
        "FILE_" + action.toUpperCase(Locale.ROOT) + "_FAILED",
        EditorEvents.Subsystem.IMPORT_EXPORT,
        error);
    show("Path");
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
