package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION;

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
  static final String APPLICATION_VERSION = "0.1.0";
  private final EditorState state = new EditorState();
  private final EditorLifecycle lifecycle = new EditorLifecycle();
  private final EditorPresentation presentation = new EditorPresentation();
  private HiddenGlfwWindow nativeWindow;
  private Consumer<String> startupObserver;
  private boolean contextOwned;
  private PropertyPanel properties;
  private ResourcePanel resources;
  private Viewports views;
  private DiagnosticsPanel diagnostics;
  private ProfilePanel captures;
  private final Map<String, FloatingWindowLayout> settingsLayouts = new HashMap<>();
  private InputSettingsPanel inputSettings;
  private ApplicationSettingsPanel applicationSettings;
  private ResourceFindPanel findPanel;
  private ThemePanel themePanel;
  private UpdateTrustPanel updateTrust;
  private WorkspacePanel workspaceSettings;
  private PerformancePanel performanceSettings;
  private PerformanceSettingsPage performancePage;
  private UiScalePanel scaleSettings;
  private AppearanceSettingsPanel appearanceSettings;
  private LivePreferencesPanel livePreferences;
  private DebugSettingsPanel debugSettings;
  private UpdateCheckPanel updateChecks;
  private final ImBoolean updateWindow = new ImBoolean(false);
  private String smokeDiagnosticDocument;
  private long smokeDiagnosticEvent;
  private boolean smokeSchedulerDrawn;
  private final UiFields fields = new UiFields();
  private final ContextHelp contextHelp = new ContextHelp(state.helpRequests);
  private HelpPanel helpPanel;
  private AboutPanel aboutPanel;
  private final WorkspaceLayout workspaceLayout = new WorkspaceLayout(state.user.workspace);
  private final ImBoolean find = new ImBoolean(false),
      logs = new ImBoolean(false),
      validation = new ImBoolean(false),
      help = new ImBoolean(false),
      about = new ImBoolean(false),
      profiler = new ImBoolean(false),
      notifications = new ImBoolean(false),
      settings = new ImBoolean(false),
      themes = new ImBoolean(false);
  private final Map<String, ImBoolean> toolWindows =
      Map.of(
          "Find",
          find,
          "Logs",
          logs,
          "Validation",
          validation,
          "Help",
          help,
          "Notifications",
          notifications,
          "Profiler",
          profiler,
          "Themes",
          themes);
  private final Map<String, Boolean> visibilityValues = new LinkedHashMap<>();
  private final StatusBarPanel status =
      new StatusBarPanel(
          state, () -> openSettings("User Settings"), () -> this.notifications.set(true));
  private MainWindowPlacement mainWindow;
  private final EditorFrameGate frames = new EditorFrameGate();
  private final UiDpiPolicy dpi = new UiDpiPolicy();
  private DpiGlfwBackend dpiBackend;
  private String settingsPage = "User Settings", settingsCategory = "Appearance";
  private ProjectFileDialog projectFiles;
  private ResourcePackageDialog packageDialog;
  private ProjectHistoryPanel projectHistory;
  private ProjectSettingsPanel projectSettings;
  private ResourceImportPanel imports;
  private boolean smokeExportDrawn;
  private int frameCount = 0;
  private long preferenceSave = 0;
  private static String[] launchArgs = new String[0];
  private int smokeFrames = 0;
  private long smokePhaseWait;

  public Desktop() {
    lifecycle.own(
        () -> {
          state.close(false);
          startupStage("state-closed");
        });
  }

  void startDesktop() {
    lifecycle.run(
        () -> {
          var config = new Configuration();
          configure(config);
          init(config);
          preRun();
        },
        this::run,
        this::postRun,
        () -> {
          frames.stop();
          presentation.close();
          UiDpi.clear();
        });
  }

  @Override
  protected void init(Configuration config) {
    nativeWindow = new HiddenGlfwWindow();
    lifecycle.own(
        () -> {
          try {
            nativeWindow.close();
          } finally {
            handle = 0;
          }
          startupStage("window-released");
        });
    nativeWindow.open(config, this::runFrame);
    handle = nativeWindow.handle();
    startupStage("window-created");
    initImGui(config);
    lifecycle.own(this::shutdownGlfw);
    if (!imGuiGlfw.init(handle, true))
      throw new IllegalStateException("Unable to initialize the ImGui window backend");
    lifecycle.own(
        () -> {
          imGuiGl3.shutdown();
          startupStage("renderer-released");
        });
    if (!imGuiGl3.init(nativeWindow.glsl()))
      throw new IllegalStateException("Unable to initialize the ImGui renderer");
    startupStage("backends-ready");
  }

  @Override
  protected void configure(Configuration config) {
    config.setTitle("Procedural Voxel Creator");
    config.setWidth(Math.min(state.user.workspace.width, WindowPlacement.BOOTSTRAP_WIDTH));
    config.setHeight(Math.min(state.user.workspace.height, WindowPlacement.BOOTSTRAP_HEIGHT));
  }

  @Override
  protected void initImGui(Configuration config) {
    super.initImGui(config);
    contextOwned = true;
    lifecycle.own(
        () -> {
          disposeImGui();
          contextOwned = false;
          startupStage("context-released");
        });
    startupStage("context-created");
    dpiBackend = new DpiGlfwBackend(handle);
    imGuiGlfw = dpiBackend;
    UiFontAtlas.install();
    state.dialogHosts.clear();
    var io = ImGui.getIO();
    io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
    io.removeConfigFlags(
        ImGuiConfigFlags.DpiEnableScaleFonts | ImGuiConfigFlags.DpiEnableScaleViewports);
    if (state.user.workspace.multiViewport) io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);
    io.setIniFilename(null);
    if (!state.user.workspace.docking.isBlank())
      ImGui.loadIniSettingsFromMemory(state.user.workspace.docking);
    applyTheme();
  }

  private GuardedNavigation sourceNavigation;

  @Override
  protected void preRun() {
    mainWindow = new MainWindowPlacement(state.user.workspace, new GlfwMainWindow(handle));
    mainWindow.restoreHidden();
    inputSettings = new InputSettingsPanel(state.user);
    updateTrust =
        new UpdateTrustPanel(
            state.user,
            UserConfig.directory().resolve("user-config.json"),
            message ->
                state.error(
                    "UPDATE_TRUST_SAVE_FAILED", EditorEvents.Subsystem.APPLICATION, message));
    updateChecks =
        new UpdateCheckPanel(
            new UpdateCheckSession(
                state.user,
                APPLICATION_VERSION,
                state.background,
                state.uiQueue::offer,
                UpdateChecker::check,
                message ->
                    state.error(
                        "UPDATE_CHECK_FAILED", EditorEvents.Subsystem.APPLICATION, message)),
            APPLICATION_VERSION,
            () -> {
              openSettings("User Settings");
              settingsCategory = "Performance";
              performancePage.showUpdates();
            },
            UpdateCheckPanel::browse,
            ImGui::setClipboardText,
            message ->
                state.error("UPDATE_LINK_FAILED", EditorEvents.Subsystem.APPLICATION, message));
    lifecycle.own(
        () -> {
          updateChecks.close();
          startupStage("updates-released");
        });
    performanceSettings =
        new PerformancePanel(
            state.user,
            UserConfig.directory().resolve("user-config.json"),
            message ->
                state.error(
                    "PERFORMANCE_SAVE_FAILED", EditorEvents.Subsystem.APPLICATION, message));
    performancePage = new PerformanceSettingsPage(performanceSettings, updateTrust);
    scaleSettings =
        new UiScalePanel(
            state.user,
            UserConfig.directory().resolve("user-config.json"),
            message ->
                state.error("UI_SCALE_SAVE_FAILED", EditorEvents.Subsystem.APPLICATION, message));
    appearanceSettings =
        new AppearanceSettingsPanel(
            state.user,
            scaleSettings,
            this::applyTheme,
            () -> themes.set(true),
            this::savePreferences);
    applicationSettings = new ApplicationSettingsPanel(state);
    projectSettings = new ProjectSettingsPanel(state);
    themePanel =
        new ThemePanel(
            state.user,
            message ->
                state.error("THEME_EDIT_FAILED", EditorEvents.Subsystem.APPLICATION, message));
    properties = new PropertyPanel(state);
    resources = new ResourcePanel(state, properties);
    resources.docking.defaults(this::resetDockLayout);
    properties.docking.defaults(this::resetDockLayout);
    workspaceSettings =
        new WorkspacePanel(
            state.user,
            toolWindows,
            this::resetDockLayout,
            this::captureVisibility,
            this::centerMainWindow);
    WorkspaceVisibility.restore(state.user.workspace)
        .forEach((name, visible) -> toolWindows.get(name).set(visible));
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
    packageDialog =
        new ResourcePackageDialog(state, resources, imports, this::packageInteractionBlocked);
    views = new Viewports(state, properties, handle);
    lifecycle.own(
        () -> {
          views.close();
          startupStage("viewports-released");
        });
    livePreferences =
        new LivePreferencesPanel(
            state.user,
            this::savePreferences,
            () -> {
              settingsCategory = "Performance";
              performancePage.showLimits();
            },
            views::retryShadows,
            () -> {
              var descriptions = new ArrayList<String>();
              var statistics = views.shadowStatistics();
              for (int i = 0; i < statistics.size(); i++) {
                var shadow = statistics.get(i);
                descriptions.add(
                    (i == 0 ? "2D" : i == 1 ? "3D Noise" : "World")
                        + ": "
                        + shadow.state()
                        + " / "
                        + shadow.bytes()
                        + " bytes / "
                        + shadow.detail());
              }
              return List.copyOf(descriptions);
            });
    debugSettings =
        new DebugSettingsPanel(
            state, views::streamingStatistics, captures::start, () -> profiler.set(true));
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
    helpPanel = new HelpPanel(state, ImGui::setClipboardText);
    var runtime = new LinkedHashMap<String, String>();
    runtime.put("Java", System.getProperty("java.runtime.version", "Unavailable"));
    runtime.put("Java VM", System.getProperty("java.vm.name", "Unavailable"));
    runtime.put("OpenGL", glGetString(GL_VERSION));
    runtime.put("OpenGL vendor", glGetString(GL_VENDOR));
    runtime.put("OpenGL renderer", glGetString(GL_RENDERER));
    runtime.put("GLSL", glGetString(GL_SHADING_LANGUAGE_VERSION));
    aboutPanel =
        new AboutPanel(
            state.user,
            new AboutDetails(APPLICATION_VERSION, CompiledProject.ENGINE_VERSION, runtime),
            ImGui::setClipboardText,
            () -> state.helpRequests.request("help.about"));
    startupStage("services-ready");
    presentation.ready();
    frames.ready();
  }

  @Override
  protected void runFrame() {
    frames.draw(
        () -> {
          try {
            super.runFrame();
          } finally {
            UiDpi.clear();
          }
        });
  }

  @Override
  protected void startFrame() {
    glClearColor(colorBg.getRed(), colorBg.getGreen(), colorBg.getBlue(), colorBg.getAlpha());
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    imGuiGl3.newFrame();
    imGuiGlfw.newFrame();
    var frame =
        dpi.sample(
            dpiBackend.mainContentScale(),
            ImGui.getIO().getDisplayFramebufferScaleY(),
            state.user.appearance.globalScale);
    UiDpi.begin(frame);
    ImGui.getIO().setFontGlobalScale(frame.fontGlobalScale());
    ImGui.newFrame();
  }

  @Override
  public void process() {
    inputSettings.beginFrame();
    state.drain();
    try {
      mainWindow.frame();
    } catch (RuntimeException failure) {
      placementError(failure);
    }
    captures.frame(ImGui.getIO().getDeltaTime(), views);
    frameCount++;
    menu();
    var chrome = status.prepare();
    int dock = DockWorkspace.draw(chrome.dock());
    workspaceLayout.build(
        () -> {
          WorkspaceDocking.defaults(dock, chrome.dock().width(), chrome.dock().height());
          resources.docking.reset();
          properties.docking.reset();
        });
    resources.draw();
    properties.draw();
    views.draw();
    sourceNavigation.draw();
    auxiliary();
    fileDialogs();
    projectFiles.draw();
    projectHistory.draw();
    status.draw();
    shortcuts();
    captureVisibility();
    if (presentation.visible() && System.nanoTime() - preferenceSave > 5_000_000_000L) {
      preferenceSave = System.nanoTime();
      captureWorkspace();
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
    var draw = ImGui.getDrawData();
    if (presentation.phase() == EditorPresentation.Phase.READY_HIDDEN
        && !presentation.frameReady(
            draw.getDisplaySizeX(),
            draw.getDisplaySizeY(),
            draw.getFramebufferScaleX(),
            draw.getFramebufferScaleY(),
            draw.getTotalVtxCount())) {
      glfwPollEvents();
      return;
    }
    imGuiGl3.renderDrawData(draw);
    if (presentation.phase() == EditorPresentation.Phase.READY_HIDDEN)
      startupStage("frame-rendered");
    presentation.present(
        () -> glfwSwapBuffers(handle),
        () -> {
          startupStage("before-show");
          glfwShowWindow(handle);
        },
        () -> {
          mainWindow.shown();
          startupStage("shown");
        },
        this::renderPlatformWindows,
        () -> glfwPollEvents());
    startupStage("presented");
  }

  private void renderPlatformWindows() {
    if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
      long previous = glfwGetCurrentContext();
      try {
        ImGui.updatePlatformWindows();
        ImGui.renderPlatformWindowsDefault();
      } finally {
        glfwMakeContextCurrent(previous);
      }
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
  }

  private void shutdownGlfw() {
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
    startupStage("window-backend-released");
  }

  @Override
  protected void dispose() {
    frames.stop();
    presentation.close();
    UiDpi.clear();
    var failure = lifecycle.close(null);
    if (failure instanceof Error error) throw error;
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure != null) throw new IllegalStateException("Editor cleanup failed", failure);
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
      if (UiScale.beginMenu("File")) {
        for (String action : List.of("New", "Open", "Save", "SaveAs", "Import", "Export"))
          if (helpMenuItem(action, Shortcuts.action(action).label(), binding(action), true))
            command(action);
        if (helpMenuItem("Exit", "Exit", "", true))
          projectFiles.workflow.request(ProjectFileWorkflow.Intent.EXIT);
        ImGui.endMenu();
      }
      if (UiScale.beginMenu("Edit")) {
        if (helpMenuItem("Undo", "Undo", binding("Undo"), state.document.canUndo()))
          command("Undo");
        if (helpMenuItem("Redo", "Redo", binding("Redo"), state.document.canRedo()))
          command("Redo");
        ImGui.endMenu();
      }
      if (UiScale.beginMenu("View")) {
        if (helpMenuItem("Find", "Find", binding("Find"), true)) command("Find");
        if (helpMenuItem(
            "FindReferences", "Find References", binding("FindReferences"), state.selected != null))
          command("FindReferences");
        if (helpMenuItem("Validate", "Validate Project", binding("Validate"), true))
          command("Validate");
        if (helpMenuItem("Voxel Manager", "Voxel Manager", "", true)) resources.showManager();
        if (helpMenuItem("Profiler", "Profiler", binding("Profiler"), true)) command("Profiler");
        if (helpMenuItem("Logs", "Logs", binding("Logs"), true)) command("Logs");
        if (helpMenuItem("Themes", "Themes", "", true)) themes.set(true);
        if (helpMenuItem("Center Main Window", "Center Main Window", "", true)) centerMainWindow();
        if (helpMenuItem("Reset Dock Layout", "Reset Dock Layout", "", true)) {
          resetDockLayout();
        }
        ImGui.endMenu();
      }
      if (UiScale.beginMenu("Settings")) {
        for (String page :
            List.of(
                "Project Settings", "Application Settings", "User Settings", "Debug & Profiling"))
          if (helpMenuItem(
              page, page, page.equals("User Settings") ? binding("Settings") : "", true))
            openSettings(page);
        ImGui.endMenu();
      }
      if (UiScale.beginMenu("Help")) {
        if (helpMenuItem("Help", "Help", binding("Help"), true)) command("Help");
        if (helpMenuItem("About", "About", "", true)) about.set(true);
        if (helpMenuItem("Check for Updates", "Check for Updates", "", true)) checkUpdates();
        ImGui.endMenu();
      }
      ImGui.endMainMenuBar();
    }
  }

  private boolean helpMenuItem(String action, String label, String shortcut, boolean enabled) {
    boolean clicked = ImGui.menuItem(label, shortcut, false, enabled);
    if (ImGui.isItemHovered()) UiScale.tooltip("Right-click for Help");
    contextHelp.item(HelpDefinitions.menuTopic(action), action);
    return clicked;
  }

  private void openFile(String action) {
    packageDialog.open(action);
  }

  private void fileDialogs() {
    packageDialog.draw();
    if (smokeFrames > 0) {
      smokeExportDrawn |= packageDialog.exportDrawn();
      if (480 - smokeFrames == 236 && packageDialog.action().equals("Export"))
        packageDialog.cancel();
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
  }

  private void auxiliary() {
    drawFind();
    drawSettings();
    diagnostics.drawLogs(logs);
    diagnostics.drawValidation(validation);
    drawHelp();
    drawProfiler();
    drawThemes();
    updateChecks.draw(updateWindow);
    diagnostics.drawNotifications(notifications, () -> logs.set(true));
    aboutPanel.draw(about);
  }

  private void drawFind() {
    findPanel.draw(find);
  }

  private void drawSettings() {
    if (!settings.get() || !settingsPage.equals("User Settings")) suspendUserSettingsActions(null);
    if (!settings.get() || !settingsPage.equals("Debug & Profiling"))
      debugSettings.suspendActions();
    if (!settings.get()) {
      projectSettings.cancel();
      return;
    }
    var windowLayout =
        settingsLayouts.computeIfAbsent(settingsPage, key -> new FloatingWindowLayout(750, 650));
    windowLayout.prepare();
    boolean visible = ImGui.begin(settingsPage, settings);
    UiScale.window(state.user, settingsPage);
    windowLayout.observe();
    if (!settings.get()) {
      projectSettings.cancel();
      debugSettings.suspendActions();
      suspendUserSettingsActions(null);
      ImGui.end();
      return;
    }
    if (!visible) {
      debugSettings.suspendActions();
      suspendUserSettingsActions(null);
      ImGui.end();
      return;
    }
    if (settingsPage.equals("Project Settings")) {
      if (projectSettings.draw()) settings.set(false);
    } else if (settingsPage.equals("Application Settings")) {
      applicationSettings.draw();
    } else if (settingsPage.equals("Debug & Profiling")) {
      debugSettings.draw();
    } else {
      AppearanceSettingsPanel.category(fields, settingsCategory, v -> settingsCategory = v);
      suspendUserSettingsActions(settingsCategory);
      switch (settingsCategory) {
        case "Appearance" -> appearanceSettings.draw();
        case "Workspace" -> workspaceSettings.draw(this::savePreferences);
        case "Input" -> inputSettings.draw(this::savePreferences);
        case "Editor" -> livePreferences.drawEditor();
        case "Visualization" -> livePreferences.drawVisualization();
        case "Performance" -> performancePage.draw(this::savePreferences);
        default -> {}
      }
    }
    if (!settings.get()) projectSettings.cancel();
    ImGui.end();
  }

  private void suspendUserSettingsActions(String visible) {
    if (!"Appearance".equals(visible)) appearanceSettings.suspendActions();
    if (!"Input".equals(visible)) inputSettings.cancelCapture();
    if (!"Performance".equals(visible)) performancePage.suspendActions();
    if (!"Workspace".equals(visible)) workspaceSettings.suspendActions();
    if (!"Editor".equals(visible)) livePreferences.suspendEditorActions();
    if (!"Visualization".equals(visible)) livePreferences.suspendVisualizationActions();
  }

  private void savePreferences() {
    try {
      state.user.save();
      state.status = "Preferences saved";
    } catch (Exception e) {
      state.notify("Error", e.getMessage());
    }
  }

  private void drawHelp() {
    state
        .helpRequests
        .take()
        .ifPresent(
            id -> {
              if (helpPanel.openTopic(id)) help.set(true);
              else state.status = "Help topic is unavailable: " + id;
            });
    helpPanel.draw(help);
  }

  private void drawProfiler() {
    if (!profiler.get()) {
      captures.suspendActions();
      return;
    }
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

  private void resetDockLayout() {
    workspaceLayout.reset(state.user.workspace);
    resources.docking.reset();
    properties.docking.reset();
  }

  private void captureVisibility() {
    toolWindows.forEach((name, flag) -> visibilityValues.put(name, flag.get()));
    WorkspaceVisibility.capture(state.user.workspace, visibilityValues);
  }

  private void centerMainWindow() {
    try {
      mainWindow.center();
    } catch (RuntimeException failure) {
      placementError(failure);
    }
  }

  private void placementError(RuntimeException failure) {
    String detail =
        Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
    if (detail.length() > 1024) detail = detail.substring(0, 1023) + "…";
    state.error("WINDOW_PLACEMENT_FAILED", EditorEvents.Subsystem.APPLICATION, detail);
  }

  private void captureWorkspace() {
    captureVisibility();
    workspaceLayout.capture(state.user.workspace, ImGui::saveIniSettingsToMemory);
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
        || projectSettings.pendingChanges()
        || (views != null && views.bookmarkInteractionPending());
  }

  private boolean packageInteractionBlocked() {
    return sourceProjectInteraction()
        || projectHistory.session.pending()
        || projectFiles.workflow.phase() != ProjectFileWorkflow.Phase.IDLE;
  }

  private boolean privateProjectInteraction() {
    return sourceProjectInteraction()
        || (packageDialog != null && packageDialog.pending())
        || (imports != null && imports.pending());
  }

  private void checkUpdates() {
    if (state.user.performance.updateEndpoint.isBlank()) {
      openSettings("User Settings");
      settingsCategory = "Performance";
      state.status = "Configure a trusted HTTPS metadata endpoint for manual update checks";
      return;
    }
    updateWindow.set(true);
    updateChecks.start();
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
    if (presentation.visible()) {
      mainWindow.capture();
      captureWorkspace();
      state.user.save();
    }
  }

  private void startupStage(String stage) {
    if (startupObserver != null) startupObserver.accept(stage);
  }

  void observeStartup(Consumer<String> observer) {
    if (nativeWindow != null)
      throw new IllegalStateException("Startup observation must be installed before launch");
    startupObserver = Objects.requireNonNull(observer);
  }

  EditorState startupState() {
    return state;
  }

  boolean startupVisible() {
    return nativeWindow != null
        && nativeWindow.handle() != 0
        && glfwGetWindowAttrib(nativeWindow.handle(), GLFW_VISIBLE) != 0;
  }

  boolean startupContextOwned() {
    return contextOwned;
  }

  boolean startupReleased() {
    return lifecycle.closed()
        && nativeWindow != null
        && nativeWindow.handle() == 0
        && !contextOwned
        && state.background.isShutdown();
  }

  int startupFrames() {
    return frameCount;
  }

  void stopStartupProbe() {
    glfwSetWindowShouldClose(handle, true);
  }

  public static void main(String[] args) {
    launchArgs = args;
    System.setProperty("joml.nounsafe", "true");
    if (java.util.Arrays.asList(args).contains("--smoke"))
      System.setProperty("java.awt.headless", "true");
    try {
      new Desktop().startDesktop();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }
}
