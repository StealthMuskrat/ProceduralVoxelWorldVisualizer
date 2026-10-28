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
  private DiagnosticsPanel diagnostics;
  private ProfilePanel captures;
  private InputSettingsPanel inputSettings;
  private ApplicationSettingsPanel applicationSettings;
  private ResourceFindPanel findPanel;
  private ThemePanel themePanel;
  private String smokeDiagnosticDocument;
  private long smokeDiagnosticEvent;
  private boolean smokeSchedulerDrawn;
  private final UiFields fields = new UiFields();
  private boolean layout = false;
  private final ImBoolean find = new ImBoolean(false),
      logs = new ImBoolean(false),
      validation = new ImBoolean(false),
      help = new ImBoolean(false),
      about = new ImBoolean(false),
      profiler = new ImBoolean(false),
      notifications = new ImBoolean(false),
      settings = new ImBoolean(false),
      themes = new ImBoolean(false);
  private final ImString search = new ImString(1024),
      pathInput = new ImString(4096),
      endpoint = new ImString(2048),
      origins = new ImString(2048);
  private String settingsPage = "User Settings", settingsCategory = "Appearance", fileAction = "";
  private boolean filePending = false;
  private boolean packageFileOpen;
  private ProjectFileDialog projectFiles;
  private ProjectHistoryPanel projectHistory;
  private ProjectSettingsPanel projectSettings;
  private ResourceImportPanel imports;
  private boolean smokeExportDrawn;
  private int frameCount = 0;
  private long preferenceSave = 0;
  private static String[] launchArgs = new String[0];
  private int smokeFrames = 0;
  private long smokePhaseWait;

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

  private GuardedNavigation sourceNavigation;

  @Override
  protected void preRun() {
    inputSettings = new InputSettingsPanel(state.user);
    applicationSettings = new ApplicationSettingsPanel(state);
    projectSettings = new ProjectSettingsPanel(state);
    themePanel = new ThemePanel(state.user);
    properties = new PropertyPanel(state);
    resources = new ResourcePanel(state, properties);
    findPanel = new ResourceFindPanel(state, properties);
    resources.findReferences =
        id -> {
          findPanel.references(id);
          find.set(true);
        };
    properties.findReferences = resources.findReferences;
    sourceNavigation = new GuardedNavigation(state, properties);
    diagnostics =
        new DiagnosticsPanel(
            state,
            target -> {
              if (!state.canNavigate(target)) return;
              if (target.settings()) {
                if (projectSettings.open(target)) {
                  settingsPage = "Project Settings";
                  settings.set(true);
                }
              } else sourceNavigation.request(target);
            });
    captures =
        new ProfilePanel(
            state,
            target -> {
              if (state.canNavigate(target)) sourceNavigation.request(target);
            });
    projectFiles =
        new ProjectFileDialog(
            state,
            () -> {
              resources.reset();
              projectSettings.cancel();
              if (projectHistory != null) projectHistory.session.clear();
              if (imports != null) imports.cancel();
            },
            () -> glfwSetWindowShouldClose(handle, true),
            () ->
                privateProjectInteraction()
                    || (projectHistory != null && projectHistory.session.pending()));
    projectHistory =
        new ProjectHistoryPanel(
            state,
            () ->
                privateProjectInteraction()
                    || projectFiles.workflow.phase() != ProjectFileWorkflow.Phase.IDLE);
    imports =
        new ResourceImportPanel(state, this::packageInteractionBlocked, () -> validation.set(true));
    views = new Viewports(state, properties, handle);
    views.tracePanel(new TracePanel(state, sourceNavigation));
    diagnostics.tracePanel(new TracePanel(state, sourceNavigation));
    glfwSetWindowCloseCallback(
        handle,
        window -> {
          glfwSetWindowShouldClose(handle, false);
          projectFiles.workflow.request(ProjectFileWorkflow.Intent.EXIT);
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
    captures.frame(ImGui.getIO().getDeltaTime(), views);
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
    sourceNavigation.draw();
    auxiliary();
    fileDialogs();
    projectFiles.draw();
    projectHistory.draw();
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
      smokeDiagnostics(480 - smokeFrames);
      int runtimeFrame = 480 - smokeFrames;
      if (runtimeFrame == 125) {
        profiler.set(true);
        captures.start();
      }
      if (runtimeFrame == 145) state.engine.profiler().stop();
      if (runtimeFrame == 150) profiler.set(false);
      if (runtimeFrame == 153) {
        openSettings("User Settings");
        settingsCategory = "Input";
        inputSettings.smokeDraft();
      }
      if (runtimeFrame == 176) {
        inputSettings.assertSmokeDrawn();
        settings.set(false);
        System.out.println("SHORTCUT_DRAFT_CONFLICT_NATIVE_PASS");
      }
      if (runtimeFrame == 177) {
        openSettings("Application Settings");
        applicationSettings.smokeDraft();
      }
      if (runtimeFrame == 199) {
        applicationSettings.assertSmokeDrawn();
        settings.set(false);
        System.out.println("APPLICATION_PROFILE_DRAFT_NATIVE_PASS");
      }
      if (runtimeFrame == 201) {
        themes.set(true);
        themePanel.smokeDraft();
      }
      if (runtimeFrame == 224) {
        themePanel.assertSmokeDrawn();
        themes.set(false);
        System.out.println("THEME_CONTRAST_DRAFT_NATIVE_PASS");
      }
      if (runtimeFrame == 225) resources.smokeBrowser(true);
      if (runtimeFrame == 226) openFile("Export");
      if (runtimeFrame == 238) {
        resources.smokeBrowser(false);
        System.out.println("RESOURCE_BROWSER_NATIVE_PASS");
        if (!smokeExportDrawn) throw new AssertionError("Export preview was not rendered");
        System.out.println("RESOURCE_EXPORT_PREVIEW_NATIVE_PASS");
      }
      if (runtimeFrame == 239) {
        resources.smokeOrganize();
        System.out.println("HIERARCHY_ORGANIZATION_NATIVE_PASS");
      }
      if (runtimeFrame == 244) {
        findPanel.smoke(true);
        find.set(true);
      }
      if (runtimeFrame == 273) {
        findPanel.smoke(false);
        find.set(false);
        System.out.println("UNIFIED_FIND_DESKTOP_NATIVE_PASS");
      }
      if (runtimeFrame == 462) {
        profiler.set(true);
        views.compactArenas();
      }
      if (runtimeFrame == 478) profiler.set(false);
      if (!views.smokePhaseReady(runtimeFrame)) {
        if (smokePhaseWait == 0) smokePhaseWait = System.nanoTime();
        if (System.nanoTime() - smokePhaseWait > 30_000_000_000L)
          throw new IllegalStateException(
              "Timed out waiting for native stage preview at phase " + runtimeFrame);
        return;
      }
      smokePhaseWait = 0;
      if (--smokeFrames == 0) {
        views.assertSmokeCompleted();
        diagnostics.assertSmokeDrawn();
        if (!smokeSchedulerDrawn)
          throw new IllegalStateException("Scheduler profiler was not rendered");
        captures.assertSmokeDrawn();
        try {
          var capture = state.engine.profiler().current().orElseThrow().snapshot();
          ProfileIO.writeJSON(UserConfig.directory().resolve("profile-capture.json"), capture);
          ProfileIO.writeCSV(UserConfig.directory().resolve("profile-capture.csv"), capture);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        System.out.println("BOUNDED_PROFILER_CAPTURE_NATIVE_PASS");
        System.out.println("FAIR_SCHEDULER_PROFILER_NATIVE_PASS");
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
            || frameCount == 140
            || frameCount == 345
            || frameCount == 350
            || frameCount == 375
            || frameCount == 395
            || frameCount == 410
            || frameCount == 435
            || frameCount == 450
            || frameCount == 455
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

  private void smokeDiagnostics(int frame) {
    if (frame == 425) {
      smokeDiagnosticDocument = ProjectIO.JSON.valueToTree(state.project()).toString();
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      var item =
          draft.processing.stream()
              .filter(i -> i.kind.equals("EXPRESSION"))
              .findFirst()
              .orElseThrow();
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
      if (state.applyDraft() || state.issues.isEmpty())
        throw new IllegalStateException("Invalid smoke draft committed");
      var issue = state.issues.getFirst();
      if (!Objects.equals(issue.itemUuid(), item.uuid))
        throw new IllegalStateException("Missing compiler control context");
      state.reportDiagnostic(issue, EditorEvents.Subsystem.PROJECT, true);
      state.reportDiagnostic(issue, EditorEvents.Subsystem.PROJECT, true);
      smokeDiagnosticEvent = state.events.snapshot().getFirst().id();
      diagnostics.reveal(smokeDiagnosticEvent);
      var target = state.events.get(smokeDiagnosticEvent).orElseThrow().target();
      if (!properties.goToProblem(target)
          || !((Noise) state.draft)
              .processing.stream()
                  .filter(i -> i.uuid.equals(item.uuid))
                  .findFirst()
                  .orElseThrow()
                  .parameters
                  .get("expression")
                  .asText()
                  .equals("sin("))
        throw new IllegalStateException("Problem navigation discarded draft");
      logs.set(true);
    }
    if (frame == 442) {
      logs.set(false);
      notifications.set(true);
    }
    if (frame == 452) {
      notifications.set(false);
      validation.set(true);
    }
    if (frame == 458) {
      validation.set(false);
      state.refreshDraft();
      state.issues = List.of();
      if (!smokeDiagnosticDocument.equals(ProjectIO.JSON.valueToTree(state.project()).toString()))
        throw new IllegalStateException("Diagnostics changed project");
      if (state.events.get(smokeDiagnosticEvent).orElseThrow().count() != 3)
        throw new IllegalStateException("Notifications did not group");
      System.out.println("DIAGNOSTIC_NAVIGATION_PRESERVES_DRAFT_AND_DOCUMENT_PASS");
    }
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
        for (String action : List.of("New", "Open", "Save", "SaveAs", "Import", "Export"))
          if (ImGui.menuItem(Shortcuts.action(action).label(), binding(action))) command(action);
        if (ImGui.menuItem("Exit")) projectFiles.workflow.request(ProjectFileWorkflow.Intent.EXIT);
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("Edit")) {
        if (ImGui.menuItem("Undo", binding("Undo"), false, state.document.canUndo()))
          command("Undo");
        if (ImGui.menuItem("Redo", binding("Redo"), false, state.document.canRedo()))
          command("Redo");
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("View")) {
        if (ImGui.menuItem("Find", binding("Find"))) command("Find");
        if (ImGui.menuItem(
            "Find References", binding("FindReferences"), false, state.selected != null))
          command("FindReferences");
        if (ImGui.menuItem("Validate Project", binding("Validate"))) command("Validate");
        if (ImGui.menuItem("Voxel Manager")) resources.showManager();
        if (ImGui.menuItem("Profiler", binding("Profiler"))) command("Profiler");
        if (ImGui.menuItem("Logs", binding("Logs"))) command("Logs");
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
          if (ImGui.menuItem(page, page.equals("User Settings") ? binding("Settings") : ""))
            openSettings(page);
        ImGui.endMenu();
      }
      if (ImGui.beginMenu("Help")) {
        if (ImGui.menuItem("Help", binding("Help"))) command("Help");
        if (ImGui.menuItem("About")) about.set(true);
        if (ImGui.menuItem("Check for Updates")) checkUpdates();
        ImGui.endMenu();
      }
      ImGui.endMainMenuBar();
    }
  }

  private void openFile(String action) {
    if (packageInteractionBlocked() || filePending || packageFileOpen || imports.pending()) {
      state.status =
          "Finish the private editor or pending project action before opening a resource package";
      return;
    }
    fileAction = action;
    if (action.equals("Export")) resources.beginExport();
    filePending = true;
    packageFileOpen = true;
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
        ImGui.beginChild("export-resources", 720, 360, true);
        resources.drawExportSelection();
        if (smokeFrames > 0) smokeExportDrawn = true;
        ImGui.endChild();
      }
      if (ImGui.button(fileAction)) {
        try {
          Path path = Path.of(pathInput.get());
          switch (fileAction) {
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
              if (!imports.open(pack.resources))
                throw new IOException("Import is blocked or the package was rejected");
            }
          }
          packageFileOpen = false;
          ImGui.closeCurrentPopup();
        } catch (Exception e) {
          state.error(
              "FILE_" + fileAction.toUpperCase(Locale.ROOT) + "_FAILED",
              Set.of("Import", "Export").contains(fileAction)
                  ? EditorEvents.Subsystem.IMPORT_EXPORT
                  : EditorEvents.Subsystem.PROJECT,
              fileAction + " failed: " + e.getMessage());
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) {
        packageFileOpen = false;
        ImGui.closeCurrentPopup();
      }
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        packageFileOpen = false;
        ImGui.closeCurrentPopup();
      }
      if (smokeFrames > 0 && 480 - smokeFrames == 236 && fileAction.equals("Export")) {
        packageFileOpen = false;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
    imports.draw();
  }

  public static final class PortablePackage {
    public int packageVersion = 1;
    public ProjectData resources = new ProjectData();
  }

  private void openSettings(String page) {
    settingsPage = page;
    settings.set(true);
    if (page.equals("Project Settings")) projectSettings.open(null);
    fields.clear();
    endpoint.set(state.user.performance.updateEndpoint);
    origins.set(String.join("\n", state.user.performance.trustedOrigins));
  }

  private void auxiliary() {
    drawFind();
    drawSettings();
    diagnostics.drawLogs(logs);
    diagnostics.drawValidation(validation);
    drawHelp();
    drawProfiler();
    drawThemes();
    diagnostics.drawNotifications(notifications, () -> logs.set(true));
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
    findPanel.draw(find);
  }

  private void drawSettings() {
    if (!settings.get()) {
      projectSettings.cancel();
      inputSettings.cancelCapture();
      return;
    }
    if (!settingsPage.equals("User Settings") || !settingsCategory.equals("Input"))
      inputSettings.cancelCapture();
    ImGui.setNextWindowSize(750, 650, ImGuiCond.FirstUseEver);
    ImGui.begin(settingsPage, settings);
    if (settingsPage.equals("Project Settings")) {
      if (projectSettings.draw()) settings.set(false);
    } else if (settingsPage.equals("Application Settings")) {
      applicationSettings.draw();
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
          state.engine.profiler().current().map(Profiler.Capture::recording).orElse(false),
          v -> {
            if (v) {
              captures.start();
              profiler.set(true);
            } else state.engine.profiler().stop();
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
        case "Input" -> inputSettings.draw();
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
              "Directional shadows",
              state.user.visualization.shadows,
              v -> state.user.visualization.shadows = v);
          if (state.user.visualization.shadows) {
            fields.integer(
                "Shadow map maximum resolution",
                state.user.visualization.shadowResolution,
                v ->
                    state.user.visualization.shadowResolution =
                        v <= 512 ? 512 : v <= 1024 ? 1024 : 2048);
            ImGui.textWrapped(
                "Triangle meshes cast shadows from complete resident geometry. GPU budget may lower"
                    + " resolution. Unlit, points, volumes and slices bypass shadows.");
            if (ImGui.button("Retry shadow rendering")) views.retryShadows();
            var shadowStats = views.shadowStatistics();
            for (int i = 0; i < shadowStats.size(); i++) {
              var shadow = shadowStats.get(i);
              ImGui.textWrapped(
                  (i == 0 ? "2D" : i == 1 ? "3D Noise" : "World")
                      + ": "
                      + shadow.state()
                      + " / "
                      + shadow.bytes()
                      + " bytes / "
                      + shadow.detail());
            }
          }
          fields.bool(
              "Volume self-shadowing",
              state.user.visualization.volumeShadows,
              v -> state.user.visualization.volumeShadows = v);
          if (state.user.visualization.volumeShadows) {
            fields.integer(
                "Volume light ray samples",
                state.user.visualization.volumeShadowSteps,
                v -> state.user.visualization.volumeShadowSteps = v <= 8 ? 8 : v <= 16 ? 16 : 32);
            ImGui.textWrapped(
                "Directional attenuation through the accepted volume only. Adds up to 32 texture"
                    + " samples per view-ray step. Unlit bypasses it; no volume regeneration is"
                    + " required.");
          }
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
    if (!settings.get()) projectSettings.cancel();
    ImGui.end();
  }

  private void drawHelp() {
    if (!help.get()) return;
    ImGui.setNextWindowSize(750, 550, ImGuiCond.FirstUseEver);
    ImGui.begin("Help", help);
    ImGui.inputText("Search features", search);
    for (var e : state.metadata.orderedControls()) {
      var c = e.getValue();
      if ((c.label + c.description + c.tooltip)
              .toLowerCase(Locale.ROOT)
              .contains(search.get().toLowerCase(Locale.ROOT))
          && ImGui.collapsingHeader(AppMetadata.uiText(c.label) + "###help" + e.getKey())) {
        ImGui.textWrapped(c.tooltip);
        ImGui.textWrapped(c.description);
      }
    }
    ImGui.end();
  }

  private void drawProfiler() {
    if (!profiler.get()) return;
    if (smokeFrames > 0) smokeSchedulerDrawn = true;
    captures.draw(
        profiler, views.streamingStatistics(), views::compactArenas, views.gpuTimingStatistics());
  }

  private void drawThemes() {
    themePanel.draw(themes);
  }

  private void applyTheme() {
    ThemeStyle.apply(ThemePalette.from(state.user.appearance));
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
    var attention = state.events.counts();
    if (ImGui.smallButton(
        "Notifications "
            + attention.unread()
            + (attention.actionRequired() > 0 ? "  !" + attention.actionRequired() : "")))
      notifications.set(true);
    ImGui.end();
    ImGui.popStyleVar(2);
  }

  private void shortcuts() {
    Shortcuts.Focus focus =
        views.flying()
            ? Shortcuts.Focus.FLYING
            : properties.curveFocused()
                ? Shortcuts.Focus.CURVE
                : resources.focused() ? Shortcuts.Focus.RESOURCES : Shortcuts.Focus.GENERAL;
    ShortcutKeys.match(state.user.input.shortcuts, focus, inputSettings.recording())
        .ifPresent(a -> command(a.id()));
  }

  private String binding(String action) {
    return Shortcuts.parse(state.user.input.shortcuts.getOrDefault(action, ""))
        .map(Shortcuts.Chord::text)
        .orElse("");
  }

  private void command(String action) {
    switch (action) {
      case "New" -> projectFiles.workflow.request(ProjectFileWorkflow.Intent.NEW);
      case "Open" -> projectFiles.workflow.request(ProjectFileWorkflow.Intent.OPEN);
      case "Save" -> projectFiles.workflow.save(false);
      case "SaveAs" -> projectFiles.workflow.save(true);
      case "Import", "Export" -> openFile(action);
      case "Undo" -> projectHistory.request(ProjectHistorySession.Direction.UNDO);
      case "Redo" -> projectHistory.request(ProjectHistorySession.Direction.REDO);
      case "Find" -> {
        find.set(true);
        findPanel.clearReferences();
      }
      case "FindReferences" -> {
        if (state.selected != null) {
          find.set(true);
          findPanel.references(state.selected);
        }
      }
      case "Validate" -> {
        state.validateProject();
        validation.set(true);
      }
      case "Settings" -> openSettings("User Settings");
      case "Help" -> help.set(true);
      case "Profiler" -> profiler.set(true);
      case "Logs" -> logs.set(true);
      case "Rename", "Copy", "Cut", "Paste", "Duplicate", "Delete" -> resources.shortcut(action);
      case "CurveDelete", "CurveUndo", "CurveRedo" -> properties.curveShortcut(action);
      default -> throw new IllegalArgumentException("Unknown editor command " + action);
    }
  }

  private boolean sourceProjectInteraction() {
    return properties.searchBlocked()
        || resources.blocksProjectTransition()
        || sourceNavigation.pending()
        || findPanel.navigationPending()
        || projectSettings.pendingChanges();
  }

  private boolean packageInteractionBlocked() {
    return sourceProjectInteraction()
        || projectHistory.session.pending()
        || projectFiles.workflow.phase() != ProjectFileWorkflow.Phase.IDLE;
  }

  private boolean privateProjectInteraction() {
    return sourceProjectInteraction()
        || filePending
        || packageFileOpen
        || (imports != null && imports.pending());
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
                  () ->
                      state.error(
                          "UPDATE_CHECK_FAILED",
                          EditorEvents.Subsystem.APPLICATION,
                          "Update check failed: " + e.getMessage()));
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
          var expression = ProcessingRegistry.create("EXPRESSION", "compiled-expression");
          expression.parameters.put(
              "expression",
              ProjectIO.JSON.valueToTree(
                  "source+(sin(x*0.01)+sin(x*0.01))*0.05+(true ? 2^3 : 1/0)*0.001"));
          n.processing.add(expression);
          n.outputBinding = Binding.earlier(expression.uuid);
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
          var parameter = new NumericDriver();
          parameter.enabled = true;
          parameter.destination = "baseHeight";
          parameter.combination = "ADD";
          parameter.inputMin = -1;
          parameter.inputMax = 1;
          parameter.outputMin = 0;
          parameter.outputMax = 2;
          l.defaults.parameterDrivers.add(parameter);
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
