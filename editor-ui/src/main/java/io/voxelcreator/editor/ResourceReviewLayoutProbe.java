package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production import/membership pages and explicit transaction recovery, without GPU. */
public final class ResourceReviewLayoutProbe {
  private static EditorState state;
  private static ResourceImportPanel imports;
  private static MembershipDialog members;
  private static ProjectData incoming;
  private static final AtomicBoolean blocked = new AtomicBoolean();
  private static int width = 1200, height = 900, validations;
  private static String mode = "import";
  private static boolean memberDone;

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
            state.change(ResourceReviewLayoutProbe::fixture), "Resource review fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var noiseDraft = (Noise) state.draft;
        noiseDraft.id = "private-noise";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        incoming = new ProjectData();
        incoming.noise2D.add(noise("shared", .25));
        incoming.noise2D.add(noise("needle", .75));
        for (int i = 0; i < 400; i++) incoming.noise2D.add(noise("bulk-" + i, .5));
        UUID needle = incoming.noise2D.get(1).uuid;
        imports = new ResourceImportPanel(state, blocked::get, () -> validations++);
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        Integer importId = null;
        for (int i = 0; i < sizes.length; i++) {
          dimensions(sizes[i], globals[i], scales[i]);
          require(imports.open(incoming), "Import layout open rejected");
          frames(8);
          if (i == 0) {
            int top = imports.submitted();
            var ids = imports.visibleIds();
            long queries = imports.queries();
            var plan = imports.session().preview();
            imports.probeScroll(15000);
            frames(5);
            require(
                top > 0
                    && top < 402
                    && imports.submitted() > 0
                    && !ids.equals(imports.visibleIds())
                    && queries == imports.queries()
                    && plan == imports.session().preview(),
                "Import scrolling rebuilt or failed to clip");
          }
          click(imports.control("filter"));
          type("needle");
          frames(5);
          require(
              imports.submitted() == 1 && imports.session().canApply(),
              "Filtered resources were not reviewed");
          int id = imports.rowIds().get(needle);
          require(importId == null || importId == id, "Import row identity changed across reflow");
          importId = id;
          cases.add(importReceipt(i, globals[i], scales[i]));
          click(imports.control("filters"));
          cases.add(importReceipt(i, globals[i], scales[i]));
          click(imports.control("issues"));
          cases.add(importReceipt(i, globals[i], scales[i]));
          click(imports.control("cancel"));
          require(!imports.pending(), "Import Cancel retained its session");
        }
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == noiseDraft
                && noiseDraft.id.equals("private-noise")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Import reflow changed private/document ownership");
        dimensions(new int[] {1200, 900}, 1, 1);
        require(imports.open(incoming), "Review-gating open rejected");
        frames(7);
        click(imports.control("filters"));
        var session = imports.session();
        session.choose(incoming.noise2D.getFirst().uuid, ResourceImport.Choice.REUSE);
        frames(5);
        require(!session.canApply(), "Filters acknowledged a changed resource plan");
        click(imports.control("apply"));
        click(imports.control("issues"));
        require(!session.canApply(), "Issues acknowledged a changed resource plan");
        click(imports.control("resources"));
        require(session.canApply(), "Resources did not acknowledge the current plan");
        var reviewed = session.preview();
        blocked.set(true);
        frames(3);
        click(imports.control("apply"));
        require(
            imports.pending() && reviewed == session.preview(),
            "Blocked Import consumed its review");
        blocked.set(false);
        releaseWithEscape(imports.control("apply"));
        require(
            !imports.pending()
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Same-frame Escape imported resources");

        // Membership owns a different explicit Properties selection for its fixture.
        mode = "members";
        state.select("set", state.project().voxelSets.getFirst().uuid);
        var memberDraft = (Membership) state.draft;
        memberDraft.id = "private-membership";
        UUID last = state.project().voxelTypes.getLast().uuid;
        Integer memberId = null;
        for (int i = 0; i < sizes.length; i++) {
          dimensions(sizes[i], globals[i], scales[i]);
          openMembers(memberDraft);
          frames(8);
          click(members.control("filter"));
          type("type-00299");
          frames(4);
          require(members.submitted() == 1, "Membership exact filter did not clip");
          int id = members.rowIds().get(last);
          require(memberId == null || memberId == id, "Membership identity changed across reflow");
          memberId = id;
          cases.add(memberReceipt(i, globals[i], scales[i]));
          click(members.control("section"));
          cases.add(memberReceipt(i, globals[i], scales[i]));
          click(members.control("cancel"));
          require(
              memberDone && members.session().count() == 1, "Membership Cancel changed selection");
        }
        dimensions(new int[] {1200, 900}, 1, 1);
        openMembers(memberDraft);
        frames(6);
        click(members.control("filter"));
        type("type-00299");
        click(members.control(last.toString()));
        require(members.session().count() == 2, "Native checkbox failed to stage a member");
        releaseWithEscape(members.control("apply"));
        require(
            memberDone
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == memberDraft
                && memberDraft.voxelTypeUuids.size() == 1,
            "Same-frame Escape committed membership");

        openMembers(memberDraft);
        frames(6);
        click(members.control("filter"));
        type("type-00299");
        click(members.control("select"));
        click(members.control("selected-only"));
        require(
            members.session().count() == 2 && members.submitted() == 1,
            "Filtered bulk membership lost unrelated selection");
        click(members.control("clear"));
        require(
            members.session().count() == 1 && members.submitted() == 0,
            "Selected-only Clear did not remove just the matching member");
        click(members.control("selected-only"));
        click(members.control("select"));
        long appliedRevision = state.revision, appliedHistory = state.document.historyBytes();
        state.document.setValidator(
            p ->
                List.of(
                    Diagnostic.error(
                        "MEMBERSHIP_LAYOUT", "/voxelSets", "Injected membership rejection")));
        click(members.control("apply"));
        require(
            !memberDone
                && members.section().equals("Issues")
                && members.session().issueCount() == 1
                && state.revision == appliedRevision
                && state.document.historyBytes() == appliedHistory
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == memberDraft,
            "Rejected membership lost transaction/private input");
        state.document.setValidator(NoiseCompiler::validate);
        new ProjectIO().save(out.resolve("membership-before.json"), state.project());
        click(members.control("apply"));
        require(
            memberDone
                && state.revision == appliedRevision + 1
                && state.draft == memberDraft
                && memberDraft.id.equals("private-membership")
                && state.project().voxelSets.getFirst().id.equals("catalog")
                && state
                    .project()
                    .voxelSets
                    .getFirst()
                    .voxelTypeUuids
                    .equals(List.of(state.project().voxelTypes.getFirst().uuid, last)),
            "Retry did not commit members only");
        var after = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "One membership Undo did not restore the document");
        state.document.redo();
        state.revision++;
        require(
            after.equals(ProjectIO.JSON.valueToTree(state.project())) && state.engine == engine,
            "One membership Redo or runtime ownership changed");
        new ProjectIO().save(out.resolve("membership-after.json"), state.project());
        openMembers(memberDraft);
        frames(5);
        state.select("voxel", last);
        frames(4);
        click(members.control("apply"));
        require(
            !memberDone && after.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Stale membership owner applied");
        click(members.control("cancel"));
        require(memberDone, "Stale membership could not Cancel");
        Files.writeString(
            out.resolve("resource-review-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("cachedImportScroll", true),
                    Map.entry("hiddenPagesDoNotReview", true),
                    Map.entry("blockedImportPreserved", true),
                    Map.entry("cancelBeforeApply", true),
                    Map.entry("filteredBulkSelection", true),
                    Map.entry("rejectedMembershipRetained", true),
                    Map.entry("membershipOnlyRetry", true),
                    Map.entry("oneUndoRedo", true),
                    Map.entry("staleMembershipBlocked", true),
                    Map.entry("privateInputPreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "RESOURCE_REVIEW_LAYOUT_NATIVE_PASS persistent resource dialogs / reviewed import /"
                + " private membership / Cancel and retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void fixture(ProjectData p) {
    p.noise2D.add(noise("editing", .125));
    p.noise2D.add(noise("shared", .25));
    for (int i = 0; i < 300; i++) {
      var type = new VoxelType();
      type.id = String.format(Locale.ROOT, "type-%05d", i);
      p.voxelTypes.add(type);
    }
    var set = new Membership();
    set.id = "catalog";
    set.voxelTypeUuids.add(p.voxelTypes.getFirst().uuid);
    p.voxelSets.add(set);
  }

  private static void dimensions(int[] size, float global, float scale) {
    width = size[0];
    height = size[1];
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Resources", scale);
    state.user.appearance.windowScales.put("Properties", scale);
  }

  private static void openMembers(Membership draft) {
    members = new MembershipDialog(state, draft);
    memberDone = false;
  }

  private static Map<String, Object> base(
      int scenario,
      String form,
      float global,
      float scale,
      DialogLayout layout,
      SettingsPageChrome chrome) {
    var modal = layout.bounds();
    require(
        DialogBounds.contains(layout.workArea().bounds(), modal)
            && DialogBounds.contains(modal, chrome.body()),
        "Resource modal/body outside work area");
    for (var rect : chrome.actions().values())
      require(DialogBounds.contains(modal, rect), "Resource action outside modal");
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", form);
    row.put("work", layout.workArea().bounds());
    row.put("modal", modal);
    row.put("body", chrome.body());
    row.put("actions", chrome.actions());
    row.put("globalScale", global);
    row.put("windowScale", scale);
    return row;
  }

  private static Map<String, Object> importReceipt(int i, float global, float scale) {
    var row = base(i, "import-" + imports.section(), global, scale, imports.layout, imports.chrome);
    row.put("rows", imports.submitted());
    row.put("list", imports.listBounds());
    row.put("stride", imports.rowStride());
    row.put("ids", imports.rowIds());
    row.put("metrics", imports.rowMetrics());
    return row;
  }

  private static Map<String, Object> memberReceipt(int i, float global, float scale) {
    var row =
        base(i, "membership-" + members.section(), global, scale, members.layout, members.chrome);
    row.put("rows", members.submitted());
    row.put("list", members.listBounds());
    row.put("stride", members.rowStride());
    row.put("ids", members.rowIds());
    row.put("metrics", members.rowMetrics());
    return row;
  }

  private static void click(float[] at) {
    require(at != null, "Missing resource review native control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void releaseWithEscape(float[] at) {
    require(at != null, "Missing resource review Apply");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
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
    frames(4);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Resource review layout fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, mode.equals("import") ? "Resources" : "Properties");
    DialogLayout.observe(state, mode.equals("import") ? "Resources" : "Properties");
    if (mode.equals("import")) imports.draw();
    else if (!memberDone) memberDone = members.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
