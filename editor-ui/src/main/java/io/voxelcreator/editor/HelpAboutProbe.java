package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred native ImGui Help/About input and actual Inspector context routing, without GL/GLFW. */
public final class HelpAboutProbe {
  private static EditorState state;
  private static HelpPanel help;
  private static AboutPanel about;
  private static PropertyPanel properties;
  private static final ImBoolean open = new ImBoolean(true);
  private static String mode = "help", copied = "";
  private static int width = 1400, height = 1200;
  private static DialogBounds.Rect window;

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
                  n.id = "committed";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        state.metadata.controls.get("noise2D.algorithm").options.put("CONSTANT", "Flat snow 雪");
        state.metadata.controls.get("noise2D.algorithm").description =
            "complete ### Unicode 雪\n" + "full guidance ".repeat(400);
        var metadata = state.metadata;
        var details =
            new AboutDetails(
                "fixture-app",
                "fixture-engine",
                Map.of(
                    "Java",
                    "fixture Java",
                    "OpenGL",
                    "fixture OpenGL",
                    "OpenGL renderer",
                    "renderer ### snow 雪\n" + "detail ".repeat(400)));
        help = new HelpPanel(state, text -> copied = text);
        about =
            new AboutPanel(
                state.user,
                details,
                text -> copied = text,
                () -> state.helpRequests.request("help.about"));
        properties = new PropertyPanel(state);
        var before = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        frames(7);
        int unfiltered = help.submittedRows();
        float unfilteredHeight = help.resultsBounds().height(), unfilteredStride = help.rowStride();
        require(
            unfiltered > 0 && unfiltered < help.session().catalog().topics().size(),
            "Help list was not clipped");
        click(help.controlAt("query"), 0);
        type("flat snow");
        require(help.submittedRows() == 1, "Option alias search failed");
        click(help.controlAt("topic:noise2D.algorithm"), 0);
        require(
            help.session().selected().id().equals("noise2D.algorithm"), "Topic selection failed");
        var cases = new ArrayList<Map<String, Object>>();
        Integer selectedId = null;
        int[][] sizes = {{1400, 1200}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = globals[i];
          state.user.appearance.windowScales.put("Help", scales[i]);
          state.user.appearance.windowScales.put("About", scales[i]);
          for (String form : List.of("topics", "guidance", "runtime", "licenses")) {
            mode = form.equals("topics") || form.equals("guidance") ? "help" : "about";
            if (form.equals("topics")) help.topics();
            else if (form.equals("guidance")) help.guidance();
            else about.section(form.equals("runtime") ? "Runtime" : "Licenses");
            frames(7);
            var chrome = mode.equals("help") ? help.chrome : about.chrome;
            require(DialogBounds.contains(window, chrome.body()), "Body outside client");
            for (var action : chrome.actions().values())
              require(DialogBounds.contains(window, action), "Persistent action outside client");
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("scenario", i);
            receipt.put("form", form);
            receipt.put("window", window);
            receipt.put("body", chrome.body());
            receipt.put("actions", chrome.actions());
            receipt.put(
                "selector", mode.equals("help") ? help.selectorBounds() : about.selectorBounds());
            receipt.put("globalScale", globals[i]);
            receipt.put("windowScale", scales[i]);
            receipt.put("selected", help.session().selected().id());
            if (mode.equals("help")) {
              receipt.put("search", help.searchBounds());
              receipt.put("rows", help.submittedRows());
              receipt.put("results", help.resultsBounds());
              receipt.put("stride", help.rowStride());
              receipt.put("ids", help.rowIds());
              if (form.equals("topics")) {
                Integer id = help.rowIds().get("noise2D.algorithm");
                require(id != null, "Filtered row was not submitted");
                if (selectedId == null) selectedId = id;
                require(selectedId.equals(id), "Topic identity changed on reflow");
              }
            }
            cases.add(receipt);
          }
        }
        width = 1400;
        height = 1200;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.clear();
        mode = "help";
        help.guidance();
        frames(7);
        long builds = help.session().catalog().builds();
        frames(5);
        require(help.session().catalog().builds() == builds, "Unchanged Help query rebuilt");
        click(help.controlAt("copy"), 0);
        String topicCopy = copied;
        require(
            topicCopy.equals(help.session().selected().copyText(help.session().catalog()))
                && topicCopy.contains(state.metadata.controls.get("noise2D.algorithm").description),
            "Complete Help copy failed");
        help.probeEnd();
        frames(3);
        click(help.controlAt("related:noise"), 0);
        frames(3);
        require(help.session().selected().id().equals("noise"), "Related topic did not open");
        click(help.controlAt("back"), 0);
        require(help.session().selected().id().equals("noise2D.algorithm"), "Back lost topic");
        mode = "about";
        about.section("Runtime");
        frames(7);
        click(about.controlAt("copy"), 0);
        String runtimeCopy = copied;
        require(runtimeCopy.equals(details.runtimeText()), "Complete runtime copy failed");
        about.section("Licenses");
        frames(7);
        click(about.controlAt("copy"), 0);
        String licenseCopy = copied;
        require(licenseCopy.equals(details.licenseText()), "License copy failed");
        click(about.controlAt("help"), 0);
        require(
            mode.equals("help") && help.session().selected().id().equals("help.about"),
            "About Help did not route");
        String aboutTopic = help.session().selected().id();
        mode = "properties";
        frames(8);
        float[] value = properties.probeSourceControl("value");
        require(value != null, "Inspector value control missing");
        click(value, 1);
        frames(3);
        var context = properties.contextHelp();
        var popup = context.popupBounds();
        var action = context.actionBounds();
        require(
            popup != null && action != null && DialogBounds.contains(popup, action),
            "Context Help action not bounded");
        click(context.actionCenter(), 0);
        require(
            mode.equals("help")
                && help.session().selected().id().equals("noise2D.algorithm.CONSTANT.value"),
            "Inspector context did not open exact control topic");
        require(
            state.metadata == metadata
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.pending(state)
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Help replaced private input or metadata");
        require(
            state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.engine == engine
                && state.engine.profiler().current().isEmpty(),
            "Help changed runtime/document state");
        require(
            ProjectIO.JSON.valueToTree(state.project()).equals(before)
                && ProjectIO.JSON.valueToTree(state.user).equals(user),
            "Help changed stored values");
        Files.writeString(out.resolve("topic.txt"), topicCopy);
        Files.writeString(out.resolve("runtime.txt"), runtimeCopy);
        Files.writeString(out.resolve("licenses.txt"), licenseCopy);
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("fontSource", "user-scale-fixture");
        report.put("runtimeSource", "fixture");
        report.put("cases", cases);
        report.put("unfilteredRows", unfiltered);
        report.put("totalTopics", help.session().catalog().topics().size());
        report.put("unfilteredHeight", unfilteredHeight);
        report.put("unfilteredStride", unfilteredStride);
        report.put("contextPopup", popup);
        report.put("contextAction", action);
        report.put("aboutTopic", aboutTopic);
        report.put("inspectorTopic", help.session().selected().id());
        for (String flag :
            List.of(
                "optionSearch",
                "relatedBack",
                "completeCopyCallbacks",
                "cachedQueries",
                "inspectorContext",
                "aboutHelpRoute",
                "projectPreserved")) report.put(flag, true);
        for (String flag :
            List.of(
                "physicalClipboardVerified",
                "physicalDpiVerified",
                "physicalDetachedVerified",
                "gpuFontTextureVerified",
                "desktopMenuGesturesVerified",
                "actualRuntimeGlVerified")) report.put(flag, false);
        Files.writeString(
            out.resolve("help-about-native.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "HELP_ABOUT_NATIVE_PASS read-only help / metadata options / context route / complete"
                + " copy / retained layout");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void click(float[] at, int button) {
    require(at != null, "Missing fixture control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(button, true);
    frame();
    io.addMouseButtonEvent(button, false);
    frames(3);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    if (mode.equals("help")) {
      help.draw(open);
      window = help.windowLayout.bounds();
    } else if (mode.equals("about")) {
      about.draw(open);
      window = about.windowLayout.bounds();
    } else properties.draw();
    state
        .helpRequests
        .take()
        .ifPresent(
            id -> {
              require(help.openTopic(id), "Unknown explicit Help route");
              mode = "help";
            });
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
