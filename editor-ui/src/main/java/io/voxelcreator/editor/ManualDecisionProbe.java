package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred Help/About/context footer decisions without an OS clipboard, GLFW or renderer. */
public final class ManualDecisionProbe {
  private static EditorState state;
  private static HelpPanel help;
  private static AboutPanel about;
  private static AboutDetails details;
  private static ContextHelp context;
  private static final ImBoolean open = new ImBoolean(true), hidden = new ImBoolean(false);
  private static String form = "Topics", contextTopic = "help.find";
  private static int width = 1200, height = 900;
  private static float global = 1, multiplier = 1;
  private static boolean collapsed,
      sourceVisible = true,
      failHelpCopy,
      failAboutCopy,
      reenterCopy,
      reenterHelp;
  private static float[] sourceCenter;
  private static final List<String> copied = new ArrayList<>(), routes = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        require(state.document.open(projectFile).isEmpty(), "Fixture reopen failed");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        state.metadata.controls.get("noise2D.algorithm").description =
            "publication A ### 雪\n" + "complete guidance 🌋 ".repeat(400);
        state.metadata.controls.get("noise2D.algorithm").options.put("CONSTANT", "Flat snow 雪");
        details =
            new AboutDetails(
                "fixture-app",
                "fixture-engine",
                Map.of(
                    "Java",
                    "fixture Java",
                    "OpenGL renderer",
                    "fixture renderer 雪\n" + "detail ".repeat(800)));
        help =
            new HelpPanel(
                state,
                text -> {
                  if (reenterCopy) {
                    help.openTopic("help.find");
                    throw new IllegalStateException("copy successor boundary");
                  }
                  if (failHelpCopy) throw new IllegalStateException("help copy boundary");
                  copied.add(text);
                });
        about = newAbout();
        context = new ContextHelp(state.helpRequests);
        help.openTopic("noise");
        help.openTopic("noise2D.algorithm");
        help.query("flat snow");
        help.topics();
        String topicA = help.session().selected().copyText(help.session().catalog());
        var cases = new ArrayList<Map<String, Object>>();
        capture(cases);
        form = "Guidance";
        help.guidance();
        capture(cases);
        form = "Runtime";
        about.section("Runtime");
        capture(cases);
        form = "Licenses";
        about.section("Licenses");
        capture(cases);
        form = "Context";
        dimensions(1200, 900, 1, 1);
        frames(4);
        openContext();
        capture(cases);
        keyEscape();
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (var mode : List.of("Topics", "Guidance", "Runtime", "Licenses")) {
          show(mode);
          for (var key : keys()) {
            String page = help.page(), query = help.query(), section = about.section();
            var selected = help.session().selected();
            long version = help.session().version();
            escape(control(key));
            require(
                copied.isEmpty()
                    && routes.isEmpty()
                    && page.equals(help.page())
                    && query.equals(help.query())
                    && section.equals(about.section())
                    && selected == help.session().selected()
                    && version == help.session().version(),
                "Escape authorized manual footer");
          }
        }
        form = "Guidance";
        for (var key : List.of("copy", "page", "back", "clear")) {
          prepareHelp();
          int id = chrome().actionIds().get(key);
          press(control(key), 0);
          help.openTopic("noise");
          long version = help.session().version();
          frame();
          release(0);
          frames(3);
          require(
              copied.isEmpty()
                  && routes.isEmpty()
                  && help.session().selected().id().equals("noise")
                  && help.page().equals("Guidance")
                  && help.query().isEmpty()
                  && version == help.session().version()
                  && id != chrome().actionIds().get(key),
              "Held Help action adopted successor topic");
        }
        for (var key : List.of("copy", "page", "back", "clear")) {
          prepareHelp();
          int id = chrome().actionIds().get(key);
          long builds = help.session().catalog().builds();
          press(control(key), 0);
          help.query(" FLAT   SNOW ");
          frame();
          release(0);
          frames(3);
          require(
              copied.isEmpty()
                  && help.query().equals(" FLAT   SNOW ")
                  && help.page().equals("Guidance")
                  && help.session().selected().id().equals("noise2D.algorithm")
                  && builds == help.session().catalog().builds()
                  && id != chrome().actionIds().get(key),
              "Normalized Help cache adopted predecessor raw-query gesture");
        }
        prepareHelp();
        press(control("copy"), 0);
        state.metadata = AppMetadata.copy(state.metadata);
        frame();
        release(0);
        frames(3);
        require(copied.isEmpty(), "Held Help Copy adopted equal metadata publication");
        prepareHelp();
        press(control("back"), 0);
        help.session().open("noise");
        help.session().open("noise2D.algorithm");
        long roundTrip = help.session().version();
        frame();
        release(0);
        frames(3);
        require(
            roundTrip == help.session().version()
                && help.session().selected().id().equals("noise2D.algorithm"),
            "Held Back consumed another round-trip history");
        help.query("");
        frames(4);
        press(control("copy"), 0);
        help.openTopic("noise2D.algorithm");
        frame();
        release(0);
        frames(3);
        require(copied.isEmpty(), "Repeated explicit context request retained old Help gesture");
        // Unpublished private metadata does not replace the immutable active manual.
        var privateMetadata = AppMetadata.copy(state.metadata);
        int sameId = chrome().actionIds().get("copy");
        press(control("copy"), 0);
        privateMetadata.controls.get("noise2D.algorithm").description =
            "unpublished private guidance";
        frame();
        release(0);
        frames(3);
        require(
            copied.equals(List.of(topicA)) && sameId == chrome().actionIds().get("copy"),
            "Private metadata changed active Help Copy");
        press(control("copy"), 0);
        var published = AppMetadata.copy(state.metadata);
        published.controls.get("noise2D.algorithm").description =
            "publication B ### 雪\n" + "complete guidance 🌋 ".repeat(400);
        state.metadata = published;
        frame();
        release(0);
        frames(3);
        require(copied.size() == 1, "Held Help Copy adopted changed metadata text");
        String topicB = help.session().selected().copyText(help.session().catalog());
        var retry = chrome().actionIds();
        failHelpCopy = true;
        click(control("copy"), 0);
        failHelpCopy = false;
        require(
            copied.size() == 1 && retry.equals(chrome().actionIds()),
            "Help Copy failure lost reviewed retry");
        reenterCopy = true;
        click(control("copy"), 0);
        reenterCopy = false;
        require(
            copied.size() == 1
                && help.session().selected().id().equals("help.find")
                && help.page().equals("Guidance"),
            "Copy failure overwrote explicit successor topic");
        help.openTopic("noise2D.algorithm");
        frames(4);
        click(control("copy"), 0);
        require(
            copied.equals(List.of(topicA, topicB)), "Help retry changed complete published text");
        for (var key : List.of("copy", "help")) {
          show("Runtime");
          int id = chrome().actionIds().get(key);
          press(control(key), 0);
          about.section("Licenses");
          frame();
          release(0);
          frames(3);
          require(
              copied.size() == 2
                  && routes.isEmpty()
                  && about.section().equals("Licenses")
                  && id != chrome().actionIds().get(key),
              "Held About action adopted another section");
        }
        show("Runtime");
        int old = chrome().actionIds().get("copy");
        press(control("copy"), 0);
        about = newAbout();
        frame();
        release(0);
        frames(3);
        require(
            copied.size() == 2 && old != chrome().actionIds().get("copy"),
            "Recreated About instance adopted predecessor Copy");
        var aboutRetry = chrome().actionIds();
        failAboutCopy = true;
        click(control("copy"), 0);
        failAboutCopy = false;
        require(
            copied.size() == 2 && aboutRetry.equals(chrome().actionIds()),
            "About Copy failure lost reviewed retry");
        // About's immutable runtime section is independent of project and Help metadata
        // publications.
        press(control("copy"), 0);
        require(state.document.open(projectFile).isEmpty(), "Unrelated same-UUID reopen failed");
        state.metadata = AppMetadata.copy(state.metadata);
        frame();
        release(0);
        frames(3);
        require(
            copied.size() == 3
                && copied.getLast().equals(details.runtimeText())
                && aboutRetry.equals(chrome().actionIds()),
            "Unrelated project/manual publication invalidated About review");
        show("Licenses");
        click(control("copy"), 0);
        require(
            copied.size() == 4 && copied.getLast().equals(details.licenseText()),
            "License copy lost complete notices");
        // Context requests retire on changed sources, missing source frames and panel replacement.
        show("Context");
        openContext();
        escape(context.actionCenter());
        require(routes.isEmpty(), "Escape authorized context Help");
        openContext();
        press(context.actionCenter(), 0);
        contextTopic = "help.about";
        frame();
        release(0);
        frames(3);
        require(routes.isEmpty(), "Changed context source adopted held Open Help");
        contextTopic = "help.find";
        openContext();
        press(context.actionCenter(), 0);
        sourceVisible = false;
        frame();
        sourceVisible = true;
        frame();
        release(0);
        frames(3);
        require(routes.isEmpty(), "Omitted context source retained held Open Help");
        openContext();
        old = context.actionId();
        press(context.actionCenter(), 0);
        keyEscape();
        click(sourceCenter, 1);
        frames(3);
        require(
            context.actionId() != 0 && old != context.actionId(),
            "Same-source popup reopening reused action identity");
        var reopened = context.actionCenter();
        ImGui.getIO().addMousePosEvent(reopened[0], reopened[1]);
        frame();
        release(0);
        frames(3);
        require(routes.isEmpty(), "Reopened context popup accepted predecessor release");
        keyEscape();
        openContext();
        old = context.actionId();
        press(context.actionCenter(), 0);
        context = new ContextHelp(state.helpRequests);
        frame();
        release(0);
        frames(3);
        require(routes.isEmpty(), "Recreated context instance adopted predecessor request");
        openContext();
        require(old != context.actionId(), "Context instance ID was reused");
        click(context.actionCenter(), 0);
        require(
            routes.equals(List.of("help.find")), "Explicit context route changed canonical topic");
        show("Licenses");
        reenterHelp = true;
        click(control("help"), 0);
        reenterHelp = false;
        require(
            routes.size() == 1 && about.section().equals("Runtime"),
            "Failed About Help overwrote successor section");
        click(control("help"), 0);
        require(routes.equals(List.of("help.find", "help.about")), "About Help retry failed");
        for (var mode : List.of("Topics", "Guidance", "Runtime", "Licenses")) {
          show(mode);
          int copies = copied.size();
          String query = help.query(), page = help.page(), section = about.section();
          press(control("copy"), 0);
          open.set(false);
          frame();
          open.set(true);
          frame();
          release(0);
          frames(3);
          require(
              copied.size() == copies
                  && query.equals(help.query())
                  && page.equals(help.page())
                  && section.equals(about.section()),
              "Hidden manual presentation retained Copy");
          press(control("copy"), 0);
          collapsed = true;
          frame();
          collapsed = false;
          frame();
          release(0);
          frames(3);
          require(copied.size() == copies, "Collapsed manual presentation retained Copy");
        }
        show("Guidance");
        help.openTopic("noise");
        help.openTopic("noise2D.algorithm");
        frames(4);
        click(control("back"), 0);
        require(
            help.session().selected().id().equals("noise")
                && help.page().equals("Guidance")
                && help.query().isEmpty(),
            "Explicit Back failed");
        click(control("page"), 0);
        help.query("missing-topic");
        frames(4);
        click(control("clear"), 0);
        require(
            help.page().equals("Topics")
                && help.query().isEmpty()
                && help.session().selected().id().equals("noise"),
            "Explicit Clear/page changed selection");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Manual decisions changed project/preferences/private/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(out.resolve("topic-a.txt"), topicA);
        Files.writeString(out.resolve("topic-b.txt"), topicB);
        Files.writeString(out.resolve("runtime.txt"), details.runtimeText());
        Files.writeString(out.resolve("licenses.txt"), details.licenseText());
        Files.writeString(
            out.resolve("manual-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("manual-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("runtimeSource", "fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldHelpOwnersBlocked", true),
                    Map.entry("queryCacheIndependent", true),
                    Map.entry("historyRoundTripScoped", true),
                    Map.entry("privateMetadataIndependent", true),
                    Map.entry("publishedMetadataScoped", true),
                    Map.entry("aboutSectionInstanceScoped", true),
                    Map.entry("aboutIndependent", true),
                    Map.entry("contextSourcesScoped", true),
                    Map.entry("callbackRetriesContained", true),
                    Map.entry("successorsPreserved", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("copied", copied),
                    Map.entry("routes", routes),
                    Map.entry("finalTopic", help.session().selected().id()),
                    Map.entry("finalPage", help.page()),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false),
                    Map.entry("actualRuntimeGlVerified", false))));
        System.out.println(
            "MANUAL_DECISIONS_NATIVE_PASS exact topic/history/metadata / independent About review /"
                + " scoped context requests / Escape / callback retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static AboutPanel newAbout() {
    return new AboutPanel(
        state,
        details,
        text -> {
          if (failAboutCopy) throw new IllegalStateException();
          copied.add(text);
        },
        () -> {
          if (reenterHelp) {
            about.section("Runtime");
            throw new IllegalStateException("about help boundary");
          }
          state.helpRequests.request("help.about");
        });
  }

  private static void prepareHelp() {
    help.openTopic("noise2D.algorithm");
    help.guidance();
    help.query("flat snow");
    frames(4);
  }

  private static void show(String mode) {
    form = mode;
    if (mode.equals("Topics")) help.topics();
    else if (mode.equals("Guidance")) help.guidance();
    else if (!mode.equals("Context")) about.section(mode);
    frames(5);
  }

  private static SettingsPageChrome chrome() {
    return switch (form) {
      case "Topics", "Guidance" -> help.chrome;
      case "Context" -> context.chrome;
      default -> about.chrome;
    };
  }

  private static List<String> keys() {
    return switch (form) {
      case "Topics", "Guidance" -> List.of("copy", "page", "back", "clear");
      case "Context" -> List.of("open");
      default -> List.of("copy", "help");
    };
  }

  private static float[] control(String key) {
    return switch (form) {
      case "Topics", "Guidance" -> help.controlAt(key);
      case "Context" -> context.actionCenter();
      default -> about.controlAt(key);
    };
  }

  private static DialogBounds.Rect window() {
    return switch (form) {
      case "Topics", "Guidance" -> help.windowLayout.bounds();
      case "Context" -> context.popupBounds();
      default -> about.windowLayout.bounds();
    };
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      if (form.equals("Context"))
        require(context.actionId() != 0, "Context popup disappeared during reflow");
      if (ids == null) ids = chrome().actionIds();
      require(ids.equals(chrome().actionIds()), "Reflow changed manual footer owner");
      cases.add(
          Map.ofEntries(
              Map.entry("form", form),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("window", window()),
              Map.entry("body", chrome().body()),
              Map.entry("actions", chrome().actions()),
              Map.entry("ids", chrome().actionIds())));
    }
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
    state.user.appearance.windowScales.put("Help", windowScale);
    state.user.appearance.windowScales.put("About", windowScale);
  }

  private static void openContext() {
    frames(3);
    click(sourceCenter, 1);
    frames(3);
    require(context.actionId() != 0, "Context action not submitted");
  }

  private static void press(float[] at, int button) {
    require(at != null, "Missing manual control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(button, true);
    frame();
  }

  private static void release(int button) {
    ImGui.getIO().addMouseButtonEvent(button, false);
    frame();
  }

  private static void click(float[] at, int button) {
    press(at, button);
    release(button);
    frames(4);
  }

  private static void keyEscape() {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
  }

  private static void escape(float[] at) {
    press(at, 0);
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    boolean helpMode = form.equals("Topics") || form.equals("Guidance"),
        aboutMode = form.equals("Runtime") || form.equals("Licenses");
    if (!helpMode) help.draw(hidden);
    if (!aboutMode) about.draw(hidden);
    if (open.get()) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      if (!form.equals("Context")) ImGui.setNextWindowFocus();
    }
    if (helpMode) help.draw(open);
    else if (aboutMode) about.draw(open);
    else if (open.get()) {
      boolean visible = ImGui.begin("Manual Context");
      ImGui.setWindowFontScale(multiplier);
      if (visible && sourceVisible) {
        ImGui.button(
            "Context source###manual-source", Math.max(1, ImGui.getContentRegionAvailX()), 0);
        var r = PopupLayout.itemBounds();
        sourceCenter = new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
        context.item(contextTopic, "manual-source");
      }
      ImGui.end();
    }
    state.helpRequests.take().ifPresent(routes::add);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
