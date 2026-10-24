package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import imgui.*;
import imgui.app.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectData.Color;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Native Java 25 desktop editor. UI/OpenGL are owned exclusively by this thread. */
public final class Desktop extends Application {
  private final EditorState state = new EditorState();
  private PropertyPanel properties;
  private ResourcePanel resources;
  private Viewports views;
  private final UiFields fields = new UiFields();
  private boolean layout = false;
  private final ImBoolean find = new ImBoolean(false),
      logs = new ImBoolean(false),
      help = new ImBoolean(false),
      about = new ImBoolean(false),
      profiler = new ImBoolean(false),
      notifications = new ImBoolean(false),
      settings = new ImBoolean(false),
      themes = new ImBoolean(false);
  private final ImString search = new ImString(1024),
      pathInput = new ImString(4096),
      endpoint = new ImString(2048),
      origins = new ImString(2048),
      logFilter = new ImString(256);
  private String settingsPage = "User Settings", settingsCategory = "Appearance", fileAction = "";
  private boolean filePending = false, guardPending = false;
  private Runnable pendingTransition;
  private ProjectData projectDraft;
  private ProjectData importCandidate;
  private ResourceImport.Plan importPlan;
  private final Map<UUID, ResourceImport.Choice> importChoices = new LinkedHashMap<>();
  private long importRevision = -1;
  private ResourceSearchIndex searchIndex;
  private long searchRevision = -1, searchChangedAt;
  private String searchKind = "All", searchState = "All", searchKey = "";
  private UUID findReference;
  private List<ResourceSearchIndex.Hit> searchResults = List.of();
  private final float[] frames = new float[1200];
  private int frameAt = 0, frameCount = 0;
  private long preferenceSave = 0;
  private static String[] launchArgs = new String[0];
  private int smokeFrames = 0;

  @Override
  protected void configure(Configuration config) {
    config.setTitle("Procedural Voxel Creator");
    config.setWidth(state.user.workspace.width);
    config.setHeight(state.user.workspace.height);
  }

  @Override
  protected void initImGui(Configuration config) {
    super.initImGui(config);
    var io = ImGui.getIO();
    io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
    if (state.user.workspace.multiViewport) io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);
    io.setIniFilename(null);
    if (!state.user.workspace.docking.isBlank())
      ImGui.loadIniSettingsFromMemory(state.user.workspace.docking);
    applyTheme();
  }

  @Override
  protected void preRun() {
    properties = new PropertyPanel(state);
    resources = new ResourcePanel(state, properties);
    views = new Viewports(state, properties, handle);
    glfwSetWindowCloseCallback(
        handle,
        window -> {
          glfwSetWindowShouldClose(handle, false);
          transition(() -> glfwSetWindowShouldClose(handle, true));
        });
    if (Arrays.asList(launchArgs).contains("--smoke")) {
      smokeFrames = 480;
      System.out.println(
          "SMOKE_ENV java="
              + System.getProperty("java.runtime.version")
              + " os="
              + System.getProperty("os.name")
              + " arch="
              + System.getProperty("os.arch")
              + " gl="
              + glGetString(GL_VERSION)
              + " renderer="
              + glGetString(GL_RENDERER));
      createSmokeProject();
      views.smokeSelections();
    } else if (launchArgs.length > 0 && !launchArgs[0].startsWith("--")) {
      try {
        var issues = state.document.open(Path.of(launchArgs[0]));
        if (!issues.isEmpty()) state.notify("Error", issues.toString());
        else state.revision++;
      } catch (Exception e) {
        state.notify("Error", e.getMessage());
      }
    }
  }

  @Override
  public void process() {
    state.drain();
    ImGui.getIO().setFontGlobalScale(state.user.appearance.globalScale);
    frames[frameAt++ % frames.length] = ImGui.getIO().getDeltaTime() * 1000;
    frameCount++;
    menu();
    int dock = ImGui.dockSpaceOverViewport();
    if (!layout && state.user.workspace.docking.isBlank()) {
      layout = true;
      var vp = ImGui.getMainViewport();
      imgui.internal.ImGui.dockBuilderRemoveNode(dock);
      imgui.internal.ImGui.dockBuilderAddNode(dock);
      imgui.internal.ImGui.dockBuilderSetNodeSize(dock, vp.getWorkSizeX(), vp.getWorkSizeY() - 26);
      ImInt left = new ImInt(), right = new ImInt(), center = new ImInt();
      imgui.internal.ImGui.dockBuilderSplitNode(dock, ImGuiDir.Left, .19f, left, center);
      imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Right, .31f, right, center);
      imgui.internal.ImGui.dockBuilderDockWindow("Resources", left.get());
      imgui.internal.ImGui.dockBuilderDockWindow("Properties", right.get());
      imgui.internal.ImGui.dockBuilderDockWindow("Visualizers", center.get());
      imgui.internal.ImGui.dockBuilderFinish(dock);
    }
    resources.draw();
    properties.draw();
    views.draw();
    auxiliary();
    fileDialogs();
    guardDialog();
    statusBar();
    shortcuts();
    if (System.nanoTime() - preferenceSave > 5_000_000_000L) {
      preferenceSave = System.nanoTime();
      state.user.workspace.docking = ImGui.saveIniSettingsToMemory();
      try {
        state.user.save();
      } catch (Exception e) {
        state.status = e.getMessage();
      }
    }
    if (smokeFrames > 0) {
      views.smokePhase(480 - smokeFrames);
      if (--smokeFrames == 0) {
        views.assertSmokeCompleted();
        System.out.println(
            "DESKTOP_SMOKE_COMPLETED frames=" + frameCount + " engine=" + state.engine.metrics());
        glfwSetWindowShouldClose(handle, true);
      }
    }
  }

  @Override
  protected void endFrame() {
    ImGui.render();
    imGuiGl3.renderDrawData(ImGui.getDrawData());
    if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
      long previous = glfwGetCurrentContext();
      ImGui.updatePlatformWindows();
      ImGui.renderPlatformWindowsDefault();
      glfwMakeContextCurrent(previous);
    }
    if (smokeFrames > 0
        && (frameCount == 70
            || frameCount == 265
            || frameCount == 290
            || frameCount == 315
            || frameCount == 110
            || frameCount == 170
            || frameCount == 195
            || frameCount == 215
            || frameCount == 230
            || frameCount == 350
            || frameCount == 375
            || frameCount == 395
            || frameCount == 410
            || frameCount == 470)) captureFrame();
    glfwSwapBuffers(handle);
    glfwPollEvents();
  }

  @Override
  protected void dispose() {
    imGuiGl3.shutdown();
    try {
      imGuiGlfw.shutdown();
    } catch (NullPointerException failure) {
      // imgui-java 1.89 can pass an unavailable X11 standard cursor (0) to GLFW.
      // Window callback disposal and glfwTerminate below still release native ownership.
      boolean missingCursor =
          java.util.Arrays.stream(failure.getStackTrace())
              .anyMatch(
                  f ->
                      f.getClassName().equals("org.lwjgl.glfw.GLFW")
                          && f.getMethodName().equals("glfwDestroyCursor"));
      if (!missingCursor) throw failure;
      System.err.println(
          "NATIVE_CURSOR_CLEANUP: unavailable platform cursor; completing GLFW shutdown");
    }
    disposeImGui();
    disposeWindow();
  }

  private void captureFrame() {
    try {
      int[] w = {0}, h = {0};
      glfwGetFramebufferSize(handle, w, h);
      var pixels = org.lwjgl.BufferUtils.createByteBuffer(w[0] * h[0] * 4);
      glReadBuffer(GL_BACK);
      glReadPixels(0, 0, w[0], h[0], GL_RGBA, GL_UNSIGNED_BYTE, pixels);
      var image =
          new java.awt.image.BufferedImage(w[0], h[0], java.awt.image.BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < h[0]; y++)
        for (int x = 0; x < w[0]; x++) {
          int at = ((h[0] - 1 - y) * w[0] + x) * 4;
          int color =
              (Byte.toUnsignedInt(pixels.get(at)) << 16)
                  | (Byte.toUnsignedInt(pixels.get(at + 1)) << 8)
                  | Byte.toUnsignedInt(pixels.get(at + 2));
          image.setRGB(x, y, color);
        }
      java.util.Set<Integer> distinct = new java.util.HashSet<>();
      for (int y = 0; y < h[0]; y += 11)
        for (int x = 0; x < w[0]; x += 11) distinct.add(image.getRGB(x, y));
      if (distinct.size() < 16)
        throw new IllegalStateException(
            "Rendered frame is blank or has insufficient visual content");
      Path path = UserConfig.directory().resolve("smoke-" + frameCount + ".png");
      javax.imageio.ImageIO.write(image, "png", path.toFile());
      glReadBuffer(GL_BACK);
      System.out.println("SMOKE_SCREENSHOT " + path);
    } catch (Exception e) {
      throw new IllegalStateException("Smoke screenshot failed", e);
    }
  }

  private void menu() {
    if (ImGui.beginMainMenuBar()) {
      if (ImGui.beginMenu("File")) {
        if (ImGui.menuItem("New"))
          transition(
              () -> {
                state.newSession();
                resources.reset();
              });
        if (ImGui.menuItem("Open Project")) transition(() -> openFile("Open"));
        if (ImGui.menuItem("Save", "Ctrl+S")) save(false);
        if (ImGui.menuItem("Save As")) save(true);
        if (ImGui.menuItem("Import")) openFile("Import");
        if (ImGui.menuItem("Export")) openFile("Export");
        if (ImGui.menuItem("Exit")) transition(() -> glfwSetWindowShouldClose(handle, true));
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("Edit")) {
        if (ImGui.menuItem("Undo", "Ctrl+Z", false, state.document.canUndo())) {
          state.document.undo();
          state.revision++;
          state.refreshDraft();
        }
        if (ImGui.menuItem("Redo", "Ctrl+Y", false, state.document.canRedo())) {
          state.document.redo();
          state.revision++;
          state.refreshDraft();
        }
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("View")) {
        if (ImGui.menuItem("Find", "Ctrl+F")) {
          find.set(true);
          findReference = null;
        }
        if (ImGui.menuItem("Find References", false, state.selected != null)) {
          find.set(true);
          findReference = state.selected;
          search.set("");
          searchKey = "";
        }
        if (ImGui.menuItem("Validate Project")) {
          state.issues = NoiseCompiler.validate(state.project());
          state.notify(
              state.issues.isEmpty() ? "Information" : "Error",
              state.issues.isEmpty() ? "Project is valid" : state.issues.toString());
        }
        if (ImGui.menuItem("Voxel Manager")) resources.showManager();
        if (ImGui.menuItem("Profiler")) profiler.set(true);
        if (ImGui.menuItem("Logs")) logs.set(true);
        if (ImGui.menuItem("Themes")) themes.set(true);
        if (ImGui.menuItem("Reset Dock Layout")) {
          state.user.workspace.docking = "";
          layout = false;
        }
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("Settings")) {
        for (String page :
            List.of(
                "Project Settings", "Application Settings", "User Settings", "Debug & Profiling"))
          if (ImGui.menuItem(page)) openSettings(page);
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("Help")) {
        if (ImGui.menuItem("Help")) help.set(true);
        if (ImGui.menuItem("About")) about.set(true);
        if (ImGui.menuItem("Check for Updates")) checkUpdates();
        ImGui.endMenu();
      }
      ImGui.endMainMenuBar();
    }
  }

  private void transition(Runnable action) {
    if (state.document.dirty()) {
      pendingTransition = action;
      guardPending = true;
    } else action.run();
  }

  private void guardDialog() {
    if (guardPending) {
      ImGui.openPopup("Unsaved Project");
      guardPending = false;
    }
    if (ImGui.beginPopupModal("Unsaved Project", ImGuiWindowFlags.AlwaysAutoResize)) {
      ImGui.text("Save changes before continuing?");
      if (ImGui.button("Save")) {
        ImGui.closeCurrentPopup();
        save(false);
      }
      ImGui.sameLine();
      if (ImGui.button("Don't Save")) {
        var next = pendingTransition;
        pendingTransition = null;
        ImGui.closeCurrentPopup();
        if (next != null) next.run();
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) {
        pendingTransition = null;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
  }

  private void save(boolean as) {
    if (as || state.document.path() == null) {
      openFile("Save");
      return;
    }
    try {
      state.document.save(state.document.path());
      state.notify("Information", "Project saved");
      completeTransition();
    } catch (Exception e) {
      state.notify("Error", "Save failed: " + e.getMessage());
      pendingTransition = null;
    }
  }

  private void completeTransition() {
    var next = pendingTransition;
    pendingTransition = null;
    if (next != null) next.run();
  }

  private void openFile(String action) {
    fileAction = action;
    if (action.equals("Export")) resources.beginExport();
    filePending = true;
    pathInput.set(
        state.document.path() != null ? state.document.path().toString() : "project.pvproj.json");
  }

  private void fileDialogs() {
    if (filePending) {
      ImGui.openPopup("File Location");
      filePending = false;
    }
    if (ImGui.beginPopupModal("File Location", ImGuiWindowFlags.AlwaysAutoResize)) {
      ImGui.text(fileAction + " file");
      ImGui.setNextItemWidth(600);
      ImGui.inputText("Path", pathInput);
      if (fileAction.equals("Export")) {
        ImGui.beginChild("export-resources", 650, 300, true);
        resources.drawExportSelection();
        ImGui.endChild();
      }
      if (ImGui.button(fileAction)) {
        Path path = Path.of(pathInput.get());
        try {
          switch (fileAction) {
            case "Open" -> {
              var issues = state.document.open(path);
              if (!issues.isEmpty()) throw new IOException(issues.toString());
              state.revision++;
              state.selected = null;
              state.draft = null;
              resources.reset();
              state.notify("Information", "Project opened");
            }
            case "Save" -> {
              state.document.save(path);
              state.notify("Information", "Project saved");
            }
            case "Export" -> {
              if (resources.exportSelection.isEmpty())
                throw new IOException("Select resources to export");
              var pack = new PortablePackage();
              pack.resources = resources.exportSelection();
              var issues = NoiseCompiler.validate(pack.resources);
              if (!issues.isEmpty()) throw new IOException(issues.toString());
              Files.writeString(path, ProjectIO.JSON.writeValueAsString(pack));
              state.notify("Information", "Resource package exported");
            }
            case "Import" -> {
              var pack = ProjectIO.JSON.readValue(path.toFile(), PortablePackage.class);
              if (pack.packageVersion != 1) throw new IOException("Unsupported package version");
              var issues = NoiseCompiler.validate(pack.resources);
              if (!issues.isEmpty()) throw new IOException(issues.toString());
              importCandidate = pack.resources;
              importChoices.clear();
              importPlan = null;
              importRevision = -1;
            }
          }
          ImGui.closeCurrentPopup();
          if (fileAction.equals("Save")) completeTransition();
        } catch (Exception e) {
          state.notify("Error", fileAction + " failed: " + e.getMessage());
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) {
        pendingTransition = null;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
    if (importCandidate != null && !ImGui.isPopupOpen("Import Resources"))
      ImGui.openPopup("Import Resources");
    if (ImGui.beginPopupModal("Import Resources", ImGuiWindowFlags.AlwaysAutoResize)) {
      if (importPlan == null || importRevision != state.revision) {
        importPlan = ResourceImport.plan(state.project(), importCandidate, importChoices);
        importRevision = state.revision;
      }
      ImGui.textWrapped(
          "Preview the entire import. Copies receive new UUIDs and collision-safe IDs. Reuse is"
              + " offered only for semantically identical definitions. Skips also exclude invalid"
              + " dependents.");
      ImGui.beginChild("import-resources", 720, 360, true);
      for (var row : importPlan.rows()) {
        ImGui.pushID(row.uuid().toString());
        ImGui.text(row.kind() + " / " + row.id());
        if (row.existing() != null)
          ImGui.textDisabled(
              "Existing identity/ID conflict: "
                  + row.existing()
                  + "; compatible: "
                  + row.compatible());
        var choice = importChoices.getOrDefault(row.uuid(), ResourceImport.Choice.COPY);
        fields.choice(
            "Resolution",
            choice.name(),
            row.compatible() ? List.of("COPY", "REUSE", "SKIP") : List.of("COPY", "SKIP"),
            v -> {
              importChoices.put(row.uuid(), ResourceImport.Choice.valueOf(v));
              importRevision = -1;
            });
        ImGui.textDisabled(
            row.resolution() + (row.targetId() == null ? "" : " -> " + row.targetId()));
        ImGui.separator();
        ImGui.popID();
      }
      for (var issue : importPlan.diagnostics()) ImGui.textWrapped("! " + issue);
      ImGui.endChild();
      if (importPlan.valid() && ImGui.button("Import Previewed Transaction")) {
        var candidate = importPlan.candidate();
        if (state.change(p -> ResourceGraph.assign(p, ProjectIO.copy(candidate)))) {
          importCandidate = null;
          importPlan = null;
          ImGui.closeCurrentPopup();
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) {
        importCandidate = null;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
  }

  public static final class PortablePackage {
    public int packageVersion = 1;
    public ProjectData resources = new ProjectData();
  }

  private void openSettings(String page) {
    settingsPage = page;
    settings.set(true);
    projectDraft = ProjectIO.copy(state.project());
    fields.clear();
    endpoint.set(state.user.performance.updateEndpoint);
    origins.set(String.join("\n", state.user.performance.trustedOrigins));
  }

  private void auxiliary() {
    drawFind();
    drawSettings();
    drawLogs();
    drawHelp();
    drawProfiler();
    drawThemes();
    drawNotifications();
    if (about.get()) {
      ImGui.begin("About", about);
      ImGui.text("Procedural Voxel Creator 0.1.0");
      ImGui.text("Noise Generator Engine " + CompiledProject.ENGINE_VERSION);
      ImGui.text("Java " + System.getProperty("java.version"));
      ImGui.text("OpenGL " + glGetString(GL_VERSION));
      ImGui.textWrapped(
          "Uses LWJGL (BSD), Dear ImGui/imgui-java (MIT), JOML (MIT), FastNoiseLite (MIT), Jackson"
              + " (Apache-2.0), JUnit (EPL-2.0). See docs/LICENSES.md for attribution and"
              + " notices.");
      ImGui.end();
    }
  }

  private void drawFind() {
    if (!find.get()) return;
    ImGui.setNextWindowSize(760, 560, ImGuiCond.FirstUseEver);
    ImGui.begin("Find", find);
    if (ImGui.inputText("ID, UUID, value or reference", search)) {
      findReference = null;
      searchChangedAt = System.nanoTime();
    }
    fields.choice(
        "Kind",
        searchKind,
        List.of(
            "All",
            "noise2D",
            "noise3D",
            "folder",
            "layer",
            "operation",
            "voxel",
            "group",
            "set",
            "transform",
            "processing",
            "parameter",
            "curve point"),
        v -> {
          searchKind = v;
          searchKey = "";
        });
    fields.choice(
        "State",
        searchState,
        List.of("All", "Enabled", "Disabled"),
        v -> {
          searchState = v;
          searchKey = "";
        });
    if (findReference != null) {
      ImGui.textDisabled("References to " + findReference);
      if (ImGui.smallButton("Clear Reference Filter")) {
        findReference = null;
        searchKey = "";
      }
    }
    boolean revisionChanged = searchRevision != state.revision;
    if (revisionChanged || searchIndex == null) {
      searchIndex = new ResourceSearchIndex(state.project());
      searchRevision = state.revision;
      searchKey = "";
    }
    String key = search.get() + "|" + searchKind + "|" + searchState + "|" + findReference;
    if (!key.equals(searchKey)
        && (revisionChanged
            || searchKey.isEmpty()
            || System.nanoTime() - searchChangedAt > 150_000_000L)) {
      searchResults = searchIndex.search(search.get(), searchKind, searchState, findReference);
      searchKey = key;
    }
    ImGui.text(searchResults.size() + " result(s)");
    ImGui.beginChild("search-results", 0, 0, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(searchResults.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var result = searchResults.get(i);
          if (ImGui.selectable(
              result.kind()
                  + " / "
                  + result.label()
                  + (result.enabled() ? "" : " [disabled]")
                  + "###result"
                  + result.uuid())) {
            if (result.owner() != null) properties.goToControl(result.owner(), result.uuid());
            else state.select(result.kind(), result.uuid());
          }
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ImGui.end();
  }

  private void drawSettings() {
    if (!settings.get()) return;
    ImGui.setNextWindowSize(750, 650, ImGuiCond.FirstUseEver);
    ImGui.begin(settingsPage, settings);
    if (settingsPage.equals("Project Settings")) {
      if (projectDraft == null) projectDraft = ProjectIO.copy(state.project());
      fields.text(
          "Project seed",
          Long.toString(projectDraft.project.seed),
          v -> {
            try {
              projectDraft.project.seed = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
          });
      fields.integer(
          "Minimum Y (inclusive)", projectDraft.project.minY, v -> projectDraft.project.minY = v);
      fields.integer(
          "Maximum Y (inclusive)", projectDraft.project.maxY, v -> projectDraft.project.maxY = v);
      if (ImGui.button("Apply")) {
        var edited = ProjectIO.copy(projectDraft);
        if (state.change(p -> p.project = edited.project)) settings.set(false);
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) settings.set(false);
    } else if (settingsPage.equals("Application Settings")) {
      ImGui.textWrapped(
          "Presentation metadata changes labels/help only. Computational IDs, types, signatures and"
              + " existing resource values are authoritative.");
      for (var e : state.metadata.controls.entrySet()) {
        ImGui.pushID(e.getKey());
        if (ImGui.treeNode(e.getKey())) {
          fields.text("Label", e.getValue().label, v -> e.getValue().label = v);
          fields.text("Tooltip", e.getValue().tooltip, v -> e.getValue().tooltip = v);
          fields.text("Description", e.getValue().description, v -> e.getValue().description = v);
          ImGui.treePop();
        }
        ImGui.popID();
      }
      if (ImGui.button("Save Metadata")) {
        state.metadata.save();
        state.status = "Application metadata saved";
      }
    } else if (settingsPage.equals("Debug & Profiling")) {
      for (String category :
          List.of(
              "General",
              "Generation",
              "World-Chunks",
              "Rendering",
              "Memory",
              "Scheduler",
              "LOD",
              "Diagnostics"))
        if (ImGui.collapsingHeader(category)) {
          var m = state.engine.metrics();
          ImGui.textWrapped(m.toString());
          ImGui.textWrapped(views.streamingStatistics().toString());
        }
      fields.bool(
          "Enable targeted profiling",
          state.user.performance.profiler,
          v -> {
            state.user.performance.profiler = v;
            state.user.save();
          });
      if (ImGui.button("Open Profiler")) profiler.set(true);
    } else {
      fields.choice(
          "Category",
          settingsCategory,
          List.of("Appearance", "Workspace", "Input", "Editor", "Visualization", "Performance"),
          v -> settingsCategory = v);
      switch (settingsCategory) {
        case "Appearance" -> {
          fields.choice(
              "Theme",
              state.user.appearance.theme,
              List.of("Dark", "Light"),
              v -> {
                state.user.appearance.theme = v;
                applyTheme();
              });
          float[] scale = {state.user.appearance.globalScale};
          if (ImGui.sliderFloat("UI scale", scale, .5f, 2.5f, "%.2fx"))
            state.user.appearance.globalScale = scale[0];
          for (String window : List.of("Resources", "Properties", "Visualizers")) {
            float[] value = {state.user.appearance.windowScales.getOrDefault(window, 1f)};
            if (ImGui.sliderFloat(window + " scale", value, .5f, 2.5f, "%.2fx"))
              state.user.appearance.windowScales.put(window, value[0]);
          }
          if (ImGui.button("Open Themes")) themes.set(true);
        }
        case "Workspace" -> {
          fields.bool(
              "Multi-viewport windows (restart)",
              state.user.workspace.multiViewport,
              v -> state.user.workspace.multiViewport = v);
          if (ImGui.button("Restore Default Docking")) {
            state.user.workspace.docking = "";
            layout = false;
          }
        }
        case "Input" -> {
          for (var key : new ArrayList<>(state.user.input.shortcuts.keySet())) {
            fields.text(
                key,
                state.user.input.shortcuts.get(key),
                v -> {
                  var old = state.user.input.shortcuts.put(key, v);
                  try {
                    state.user.validate();
                  } catch (Exception e) {
                    state.user.input.shortcuts.put(key, old);
                    state.status = e.getMessage();
                  }
                });
          }
          ImGui.textWrapped("Use Ctrl+letter combinations. Mouse navigation gestures are fixed.");
        }
        case "Editor" -> {
          fields.bool(
              "Auto regenerate on changes",
              state.user.editor.autoRegenerate,
              v -> state.user.editor.autoRegenerate = v);
          int[] rate = {state.user.editor.opportunitiesPerSecond};
          if (ImGui.sliderInt("Opportunities / second", rate, 1, 20))
            state.user.editor.opportunitiesPerSecond = rate[0];
        }
        case "Visualization" -> {
          fields.bool(
              "Grid", state.user.visualization.grid, v -> state.user.visualization.grid = v);
          fields.bool(
              "Unlit", state.user.visualization.unlit, v -> state.user.visualization.unlit = v);
          fields.bool(
              "Camera-driven chunk streaming",
              state.user.visualization.streaming,
              v -> state.user.visualization.streaming = v);
          fields.bool(
              "Ambient occlusion",
              state.user.visualization.ambientOcclusion,
              v -> state.user.visualization.ambientOcclusion = v);
          fields.bool(
              "Distance fog", state.user.visualization.fog, v -> state.user.visualization.fog = v);
          fields.bool(
              "Indirect drawing when supported",
              state.user.visualization.indirectDrawing,
              v -> state.user.visualization.indirectDrawing = v);
          fields.integer(
              "Default preview extent",
              state.user.visualization.previewSize,
              v ->
                  state.user.visualization.previewSize =
                      Math.max(2, Math.min(state.user.performance.maxPreviewDimension, v)));
        }
        case "Performance" -> {
          fields.integer(
              "Worker threads (restart)",
              state.user.performance.workers,
              v -> state.user.performance.workers = Math.max(1, Math.min(64, v)));
          fields.integer(
              "Maximum preview dimension",
              state.user.performance.maxPreviewDimension,
              v -> state.user.performance.maxPreviewDimension = Math.max(2, Math.min(256, v)));
          fields.integer(
              "Cache MiB",
              state.user.performance.cacheMiB,
              v -> state.user.performance.cacheMiB = Math.max(0, v));
          fields.integer(
              "Engine memory MiB (restart)",
              state.user.performance.engineMiB,
              v -> state.user.performance.engineMiB = Math.max(64, Math.min(4096, v)));
          fields.integer(
              "Streaming CPU MiB per view",
              state.user.performance.sceneCpuMiB,
              v -> state.user.performance.sceneCpuMiB = Math.max(16, Math.min(4096, v)));
          fields.integer(
              "Streaming GPU MiB per view",
              state.user.performance.sceneGpuMiB,
              v -> state.user.performance.sceneGpuMiB = Math.max(8, Math.min(256, v)));
          fields.integer(
              "Upload KiB per frame",
              state.user.performance.uploadKiB,
              v -> state.user.performance.uploadKiB = Math.max(64, Math.min(16384, v)));
          fields.integer(
              "Render distance in chunks",
              state.user.performance.renderDistance,
              v -> state.user.performance.renderDistance = Math.max(0, Math.min(4, v)));
          fields.integer(
              "Vertical distance in chunks",
              state.user.performance.verticalDistance,
              v -> state.user.performance.verticalDistance = Math.max(0, Math.min(2, v)));
          fields.integer(
              "LOD start distance in chunks",
              state.user.performance.lodStart,
              v -> state.user.performance.lodStart = Math.max(1, Math.min(16, v)));
          fields.integer(
              "LOD quality (0 low, 2 high)",
              state.user.performance.lodQuality,
              v -> state.user.performance.lodQuality = Math.max(0, Math.min(2, v)));
          fields.integer(
              "Resident streaming chunk limit",
              state.user.performance.streamChunks,
              v -> state.user.performance.streamChunks = Math.max(27, Math.min(2048, v)));
          ImGui.inputText("Manual update endpoint", endpoint);
          ImGui.inputTextMultiline("Approved HTTPS origins (one per line)", origins, 500, 90);
          if (ImGui.button("Apply Update Trust")) {
            try {
              for (String origin : origins.get().lines().filter(x -> !x.isBlank()).toList())
                UpdateChecker.origin(java.net.URI.create(origin));
              if (!endpoint.get().isBlank())
                UpdateChecker.origin(java.net.URI.create(endpoint.get()));
              state.user.performance.updateEndpoint = endpoint.get();
              state.user.performance.trustedOrigins =
                  origins.get().lines().filter(x -> !x.isBlank()).toList();
              state.user.save();
            } catch (Exception e) {
              state.notify("Error", e.getMessage());
            }
          }
        }
        default -> {}
      }
      if (ImGui.button("Save Preferences")) {
        try {
          state.user.save();
          state.status = "Preferences saved";
        } catch (Exception e) {
          state.notify("Error", e.getMessage());
        }
      }
    }
    ImGui.end();
  }

  private void drawLogs() {
    if (!logs.get()) return;
    ImGui.setNextWindowSize(800, 500, ImGuiCond.FirstUseEver);
    ImGui.begin("Logs & Error Center", logs);
    ImGui.inputText("Filter", logFilter);
    for (String entry : state.logs)
      if (logFilter.get().isBlank()
          || entry.toLowerCase(Locale.ROOT).contains(logFilter.get().toLowerCase(Locale.ROOT)))
        ImGui.textWrapped(entry);
    if (ImGui.button("Copy Details")) ImGui.setClipboardText(String.join("\n", state.logs));
    ImGui.end();
  }

  private void drawNotifications() {
    if (!notifications.get()) return;
    ImGui.setNextWindowSize(600, 400, ImGuiCond.FirstUseEver);
    ImGui.begin("Notifications", notifications);
    if (ImGui.button("Mark All Read"))
      for (int i = 0; i < state.notices.size(); i++) {
        var n = state.notices.get(i);
        state.notices.set(
            i, new EditorState.Notice(n.severity(), n.message(), n.time(), n.count(), true));
      }
    for (var notice : state.notices) {
      ImGui.separator();
      ImGui.text(notice.severity() + "  x" + notice.count() + "  " + notice.time());
      ImGui.textWrapped(notice.message());
      if (notice.severity().equals("Error") && ImGui.smallButton("View Error##" + notice.time()))
        logs.set(true);
    }
    ImGui.end();
  }

  private void drawHelp() {
    if (!help.get()) return;
    ImGui.setNextWindowSize(750, 550, ImGuiCond.FirstUseEver);
    ImGui.begin("Help", help);
    ImGui.inputText("Search features", search);
    for (var e : state.metadata.controls.entrySet()) {
      var c = e.getValue();
      if ((c.label + c.description + c.tooltip)
              .toLowerCase(Locale.ROOT)
              .contains(search.get().toLowerCase(Locale.ROOT))
          && ImGui.collapsingHeader(c.label)) {
        ImGui.textWrapped(c.tooltip);
        ImGui.textWrapped(c.description);
      }
    }
    ImGui.end();
  }

  private void drawProfiler() {
    if (!profiler.get()) return;
    ImGui.setNextWindowSize(650, 500, ImGuiCond.FirstUseEver);
    ImGui.begin("Profiler", profiler);
    ImGui.textWrapped(state.engine.metrics().toString());
    ImGui.text("Background preview queue: " + state.background.getQueue().size());
    int n = Math.min(frameAt, frames.length);
    if (n > 0) {
      float[] samples = Arrays.copyOf(frames, n);
      Arrays.sort(samples);
      ImGui.text(
          String.format(
              Locale.ROOT,
              "Frame ms P50 %.2f  P95 %.2f  P99 %.2f  Samples %d / %d",
              samples[(n - 1) / 2],
              samples[(int) ((n - 1) * .95)],
              samples[(int) ((n - 1) * .99)],
              n,
              frames.length));
      ImGui.plotHistogram(
          "Frame times (ms)",
          frames,
          n,
          0,
          "Bounded frame capture",
          0,
          100,
          ImGui.getContentRegionAvailX(),
          150);
    }
    var runtime = Runtime.getRuntime();
    ImGui.text(
        "Heap bytes: "
            + (runtime.totalMemory() - runtime.freeMemory())
            + " / "
            + runtime.maxMemory());
    if (ImGui.button("Reset Capture")) {
      frameAt = 0;
      Arrays.fill(frames, 0);
    }
    ImGui.textWrapped(views.streamingStatistics().toString());
    ImGui.end();
  }

  private void drawThemes() {
    if (!themes.get()) return;
    ImGui.setNextWindowSize(600, 600, ImGuiCond.FirstUseEver);
    ImGui.begin("Themes", themes);
    fields.choice(
        "Starting appearance",
        state.user.appearance.theme,
        List.of("Dark", "Light"),
        v -> {
          state.user.appearance.theme = v;
          state.user.appearance.colors.clear();
          applyTheme();
        });
    for (int i = 0; i < ImGuiCol.COUNT; i++) {
      final int color = i;
      float[] rgba = {
        ImGui.getStyle().getColor(i).x,
        ImGui.getStyle().getColor(i).y,
        ImGui.getStyle().getColor(i).z,
        ImGui.getStyle().getColor(i).w
      };
      if (ImGui.colorEdit4(ImGui.getStyleColorName(i), rgba)) {
        state.user.appearance.colors.put(i, rgba);
        ImGui.getStyle().setColor(i, rgba[0], rgba[1], rgba[2], rgba[3]);
      }
    }
    ImGui.textWrapped(
        "Keep text and control backgrounds clearly distinguishable. Error messages also use !"
            + " markers.");
    if (ImGui.button("Reset to Default")) {
      state.user.appearance.theme = "Dark";
      state.user.appearance.colors.clear();
      applyTheme();
    }
    if (ImGui.button("Save Theme")) state.user.save();
    ImGui.end();
  }

  private void applyTheme() {
    if (state.user.appearance.theme.equals("Light")) ImGui.styleColorsLight();
    else ImGui.styleColorsDark();
    var style = ImGui.getStyle();
    style.setWindowRounding(5);
    style.setFrameRounding(4);
    style.setGrabRounding(4);
    style.setFramePadding(7, 5);
    style.setItemSpacing(8, 7);
    if (state.user.appearance.theme.equals("Dark")) {
      style.setColor(ImGuiCol.WindowBg, .065f, .078f, .1f, 1);
      style.setColor(ImGuiCol.Header, .15f, .24f, .29f, 1);
      style.setColor(ImGuiCol.Button, .13f, .25f, .29f, 1);
      style.setColor(ImGuiCol.TabActive, .15f, .35f, .39f, 1);
      style.setColor(ImGuiCol.CheckMark, .5f, .85f, .75f, 1);
    }
    state.user.appearance.colors.forEach((key, c) -> style.setColor(key, c[0], c[1], c[2], c[3]));
  }

  private void statusBar() {
    var vp = ImGui.getMainViewport();
    ImGui.setNextWindowViewport(vp.getID());
    ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 0, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8, 4);
    ImGui.setNextWindowPos(vp.getPosX(), vp.getPosY() + vp.getSizeY() - 28);
    ImGui.setNextWindowSize(vp.getSizeX(), 28);
    ImGui.begin(
        "Status",
        ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoDocking
            | ImGuiWindowFlags.NoSavedSettings);
    String status =
        state.status.length() > 120 ? state.status.substring(0, 120) + "..." : state.status;
    ImGui.text(status);
    ImGui.sameLine(Math.max(200, vp.getSizeX() - 260));
    if (ImGui.smallButton("Settings")) openSettings("User Settings");
    ImGui.sameLine();
    long unread = state.notices.stream().filter(n -> !n.read()).count();
    if (ImGui.smallButton("Notifications " + unread)) notifications.set(true);
    ImGui.end();
    ImGui.popStyleVar(2);
  }

  private void shortcuts() {
    if (ImGui.getIO().getWantTextInput()) return;
    if (chord("Save")) save(false);
    if (chord("Undo")) {
      state.document.undo();
      state.revision++;
      state.refreshDraft();
    }
    if (chord("Redo")) {
      state.document.redo();
      state.revision++;
      state.refreshDraft();
    }
    if (chord("Find")) {
      find.set(true);
      findReference = null;
    }
  }

  private boolean chord(String action) {
    String binding = state.user.input.shortcuts.getOrDefault(action, "");
    boolean ctrl = binding.startsWith("Ctrl+");
    String key = ctrl ? binding.substring(5) : binding;
    if (key.length() != 1) return false;
    int code = ImGuiKey.A + Character.toUpperCase(key.charAt(0)) - 'A';
    return ImGui.getIO().getKeyCtrl() == ctrl && ImGui.isKeyPressed(code, false);
  }

  private void checkUpdates() {
    if (state.user.performance.updateEndpoint.isBlank()) {
      openSettings("User Settings");
      settingsCategory = "Performance";
      state.status = "Configure a trusted HTTPS metadata endpoint for manual update checks";
      return;
    }
    try {
      String endpoint = state.user.performance.updateEndpoint;
      List<String> trust = List.copyOf(state.user.performance.trustedOrigins);
      state.background.execute(
          () -> {
            try {
              var release = UpdateChecker.check(endpoint, trust);
              state.uiQueue.offer(
                  () ->
                      state.notify(
                          "Information",
                          "Release " + release.version() + ": " + release.releaseUrl()));
            } catch (Exception e) {
              state.uiQueue.offer(
                  () -> state.notify("Error", "Update check failed: " + e.getMessage()));
            }
          });
    } catch (Exception e) {
      state.notify("Error", e.getMessage());
    }
  }

  private void createSmokeProject() {
    state.change(
        p -> {
          p.project.minY = -16;
          p.project.maxY = 63;
          var type = new VoxelType();
          type.id = "stone";
          type.color = new Color(114, 164, 143);
          p.voxelTypes.add(type);
          var n = new Noise();
          n.id = "terrain";
          n.algorithm.parameters.put("frequency", .035);
          p.noise2D.add(n);
          var volume = new Noise();
          volume.id = "caves";
          volume.dimension = 3;
          volume.algorithm.parameters.put("frequency", .08);
          p.noise3D.add(volume);
          var l = new Layer();
          l.id = "terrain-layer";
          l.defaults.action = "SET";
          l.defaults.voxelTypeUuid = type.uuid;
          l.defaults.baseHeight = 12;
          l.defaults.heightScale = 14;
          var op = new Operation();
          op.id = "height";
          op.noiseGeneratorUuid = n.uuid;
          l.operations.add(op);
          var clear = new Operation();
          clear.id = "carve-caves";
          clear.noiseGeneratorUuid = volume.uuid;
          clear.interpretation = "DENSITY";
          clear.overrides.put("action", ProjectIO.JSON.valueToTree("CLEAR"));
          l.operations.add(clear);
          var clay = new VoxelType();
          clay.id = "clay";
          clay.color = new Color(184, 130, 89);
          p.voxelTypes.add(clay);
          l.defaults.typeSource = "INTERVALS";
          l.defaults.voxelTypeUuid = null;
          var low = new TypeInterval();
          low.min = -2;
          low.max = 0;
          low.voxelTypeUuid = type.uuid;
          var high = new TypeInterval();
          high.min = 0;
          high.max = 2;
          high.includeMax = true;
          high.voxelTypeUuid = clay.uuid;
          l.defaults.typeIntervals.addAll(List.of(low, high));
          var shift = new SamplingTransform();
          shift.x = 3;
          l.defaults.samplingTransforms.add(shift);
          var turn = new SamplingTransform();
          turn.kind = "ROTATE";
          turn.y = 15;
          l.defaults.samplingTransforms.add(turn);
          l.defaults.probabilityDriver.enabled = true;
          l.defaults.probabilityDriver.inputMin = -1;
          l.defaults.probabilityDriver.outputMin = 1;
          l.defaults.probabilityDriver.outputMax = 1;
          l.defaults.fieldGates.kind = "NOT";
          var never = new Gate();
          never.kind = "PROBABILITY";
          never.probability = 0;
          l.defaults.fieldGates.children.add(never);
          p.worldGenerator.layers.add(l);
        });
    try {
      Path output = UserConfig.directory().resolve("smoke-project.json");
      state.document.save(output);
      var loaded = new ProjectLoader().load(output);
      if (!loaded.success()) throw new IllegalStateException(loaded.diagnostics().toString());
      System.out.println("SMOKE_PROJECT_SAVE_OPEN_PASS " + output);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void postRun() {
    state.user.workspace.docking = ImGui.saveIniSettingsToMemory();
    views.close();
    state.close();
  }

  public static void main(String[] args) {
    launchArgs = args;
    System.setProperty("joml.nounsafe", "true");
    if (java.util.Arrays.asList(args).contains("--smoke"))
      System.setProperty("java.awt.headless", "true");
    try {
      launch(new Desktop());
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }
}
