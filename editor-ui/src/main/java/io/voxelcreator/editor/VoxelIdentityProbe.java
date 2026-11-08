package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native identity disclosure, references, private input and both rename entry points. */
public final class VoxelIdentityProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static ResourcePanel resources;
  private static ResourceFindPanel find;
  private static final ImBoolean findOpen = new ImBoolean(false);
  private static int width = 650, height = 900;
  private static float scale = 1;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(VoxelIdentityProbe::fixture), "Fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        UUID stone = state.project().voxelTypes.getFirst().uuid;
        state.select("voxel", stone);
        var draft = (VoxelType) state.draft;
        draft.id = "private-stone";
        draft.color.r = 17;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        properties = new PropertyPanel(state);
        resources = new ResourcePanel(state, properties);
        find = new ResourceFindPanel(state, properties);
        var requests = new ArrayList<UUID>();
        java.util.function.Consumer<UUID> references =
            id -> {
              requests.add(id);
              find.references(id);
              findOpen.set(true);
            };
        resources.findReferences = references;
        properties.findReferences = references;
        resources.showManager();
        frames(6);
        require(
            resources.voxelIdentity.type().dependents().size() == 2051
                && properties.voxelIdentity.proposed().equals("private-stone")
                && resources.voxelIdentity.proposed().equals("stone"),
            "Committed/private identities mixed");
        long builds = state.voxelIdentities.builds();
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{650, 900}, {360, 900}, {720, 1600}};
        float[] scales = {1, 2.5f, 6.25f};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          scale = scales[i];
          resources.voxelIdentity.scroll(1000000);
          properties.voxelIdentity.scroll(1000000);
          frames(7);
          for (var view : List.of(resources.voxelIdentity, properties.voxelIdentity)) {
            require(view.submitted() > 0 && view.submitted() <= 40, "Dependent rows not clipped");
            require(
                DialogBounds.contains(view.bounds(), view.referenceBounds()),
                "Reference action outside review");
            cases.add(
                Map.of(
                    "scenario",
                    i,
                    "host",
                    view == resources.voxelIdentity ? "manager" : "properties",
                    "bounds",
                    view.bounds(),
                    "action",
                    view.referenceBounds(),
                    "submitted",
                    view.submitted(),
                    "scale",
                    scale));
          }
        }
        width = 650;
        height = 900;
        scale = 1;
        frames(6);
        require(
            state.voxelIdentities.builds() == builds,
            "Reflow or scrolling rebuilt identity metadata");
        click(resources.voxelIdentity.referencesAt());
        require(
            requests.equals(List.of(stone)) && findOpen.get(),
            "Manager did not open real Find References");
        findOpen.set(false);
        frames(3);
        click(properties.voxelIdentity.referencesAt());
        require(
            requests.equals(List.of(stone, stone)) && findOpen.get(),
            "Properties did not open real Find References");
        findOpen.set(false);
        frames(3);
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.revision == revision
                && state.document.historyBytes() == history
                && !state.document.dirty(),
            "Identity review changed committed/private/history state");
        state.refreshDraft(); // Explicitly retire the fixture's private edits before the rename
        // phase.
        properties.probeBodyScroll(0);
        frames(5);
        typeAt(properties.probeIdentityInput(), "granite", false);
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && properties.voxelIdentity.proposed().equals("granite"),
            "Proposed Properties ID published early");
        click(properties.probeAction("apply"));
        frames(4);
        save(out, "properties");
        require(
            state.project().voxelTypes.getFirst().id.equals("granite"), "Properties rename failed");
        var renamed = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        state.refreshDraft();
        save(out, "properties-undone");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Properties rename was not one Undo");
        state.document.redo();
        state.revision++;
        state.refreshDraft();
        save(out, "properties-redone");
        require(
            renamed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Properties Redo differed");
        frames(4);
        resources.probeVoxelRename(stone);
        frames(4);
        require(resources.probeVoxelRenameInput() != null, "Inline ID input absent");
        typeAt(resources.probeVoxelRenameInput(), "marble", true);
        frames(4);
        save(out, "inline");
        require(state.project().voxelTypes.getFirst().id.equals("marble"), "Inline rename failed");
        var inline = ProjectIO.JSON.valueToTree(state.project());
        resources.probeVoxelRename(stone);
        frames(4);
        typeAt(resources.probeVoxelRenameInput(), "snow", true);
        frames(3);
        require(
            inline.equals(ProjectIO.JSON.valueToTree(state.project()))
                && resources.voxelIdentity.proposed().equals("snow"),
            "Duplicate ID was committed or discarded");
        key(ImGuiKey.Escape, true);
        key(ImGuiKey.Escape, false);
        frames(3);
        require(resources.probeVoxelRenameInput() == null, "Manager Escape retained inline rename");
        state.document.undo();
        state.revision++;
        state.refreshDraft();
        save(out, "inline-undone");
        require(
            renamed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Inline rename was not one Undo");
        state.document.redo();
        state.revision++;
        state.refreshDraft();
        save(out, "inline-redone");
        require(inline.equals(ProjectIO.JSON.valueToTree(state.project())), "Inline Redo differed");
        frames(4);
        renameHistory(out, stone);
        longIds(out, stone);
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("voxel-identity-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "dependents",
                    resources.voxelIdentity.type().dependents(),
                    "initialBuilds",
                    builds,
                    "referenceRequests",
                    requests,
                    "type",
                    stone,
                    "privatePreserved",
                    true,
                    "duplicateRejected",
                    true,
                    "managerEscape",
                    true,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "VOXEL_IDENTITY_NATIVE_PASS cached clipped review / exact reference routes / private"
                + " preservation / Properties-inline rename / Undo / duplicate / Escape / long IDs");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void renameHistory(Path out, UUID type) throws Exception {
    var before = ProjectIO.JSON.valueToTree(state.project());
    long history = state.document.historyBytes();
    String source = state.project().voxelTypes.getFirst().id;
    var receipts = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("escape", "enter")) {
      resources.probeVoxelRename(type);
      frames(4);
      int oldId = resources.probeRenameNativeId();
      appendAt(resources.probeVoxelRenameInput(), "-draft");
      restoreRename(source, ImGuiKey.Z);
      restoreRename(source + "-draft", ImGuiKey.Y);
      restoreRename(source, ImGuiKey.Z);
      require(resources.probeRenameNativeId() == oldId, "Rename text ID changed within its opening");
      int close = mode.equals("escape") ? ImGuiKey.Escape : ImGuiKey.Enter;
      key(close, true);
      key(close, false);
      frames(4);
      require(resources.probeVoxelRenameInput() == null, "Rename predecessor did not close");
      resources.probeVoxelRename(type);
      frames(4);
      int newId = resources.probeRenameNativeId();
      require(oldId != newId && oldId != 0 && newId != 0, "Rename reused its native history ID");
      renameHistoryKey(ImGuiKey.Y);
      require(resources.voxelIdentity.proposed().equals(source), "Rename revived a discarded native Redo");
      renameHistoryKey(ImGuiKey.Z);
      require(resources.voxelIdentity.proposed().equals(source), "Rename revived discarded text");
      receipts.add(Map.of("mode", mode, "oldWidgetId", oldId, "newWidgetId", newId,
          "source", source, "restored", resources.voxelIdentity.proposed()));
      key(ImGuiKey.Escape, true);
      key(ImGuiKey.Escape, false);
      frames(3);
      require(before.equals(ProjectIO.JSON.valueToTree(state.project()))
              && history == state.document.historyBytes(), "Native rename history published a change");
    }
    save(out, "rename-history-after");
    Files.writeString(out.resolve("rename-native-history.json"), ProjectIO.JSON.writeValueAsString(
        Map.of("formatVersion", 1, "cases", receipts)));
  }

  private static void restoreRename(String expected, int key) {
    for (int i = 0; i < 8 && !resources.voxelIdentity.proposed().equals(expected); i++)
      renameHistoryKey(key);
    require(resources.voxelIdentity.proposed().equals(expected), "Native rename history lost exact text");
  }

  private static void renameHistoryKey(int code) {
    key(ImGuiKey.ModCtrl, true);
    key(code, true);
    key(code, false);
    key(ImGuiKey.ModCtrl, false);
    frames(3);
  }

  private static void longIds(Path out, UUID type) throws Exception {
    String[] ids = {"a".repeat(257), "x".repeat(255) + "é界😀", "é界😀".repeat(2049)};
    String inlineSuffix = "-inline-" + "z".repeat(300);
    String propertiesSuffix = "-properties-" + "😀".repeat(80);
    for (int i = 0; i < ids.length; i++) {
      String id = ids[i], prefix = "long-" + i + "-";
      require(state.change(p -> p.voxelTypes.getFirst().id = id), "Long ID fixture rejected");
      state.refreshDraft();
      properties.probeBodyScroll(0);
      frames(5);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes();
      save(out, prefix + "before");
      resources.probeVoxelRename(type);
      frames(4);
      require(resources.probeVoxelRenameInput() != null, "Long ID rename input absent");
      key(ImGuiKey.Enter, true);
      key(ImGuiKey.Enter, false);
      frames(4);
      require(
          before.equals(ProjectIO.JSON.valueToTree(state.project()))
              && state.document.historyBytes() == history
              && resources.probeVoxelRenameInput() == null,
          "Accepting an unchanged long ID truncated it or created history");
      save(out, prefix + "unchanged");
      resources.probeVoxelRename(type);
      frames(4);
      appendAt(resources.probeVoxelRenameInput(), inlineSuffix);
      require(
          before.equals(ProjectIO.JSON.valueToTree(state.project())), "Inline text published early");
      key(ImGuiKey.Enter, true);
      key(ImGuiKey.Enter, false);
      frames(4);
      require(
          state.project().voxelTypes.getFirst().id.equals(id + inlineSuffix), "Long inline ID clipped");
      save(out, prefix + "inline");
      historyRoundTrip(out, prefix + "inline");
      properties.probeBodyScroll(0);
      frames(5);
      appendAt(properties.probeIdentityInput(), propertiesSuffix);
      require(
          state.project().voxelTypes.getFirst().id.equals(id + inlineSuffix)
              && ((VoxelType) state.draft).id.equals(id + inlineSuffix + propertiesSuffix),
          "Long Properties text clipped or published early");
      click(properties.probeAction("apply"));
      frames(4);
      require(
          state.project().voxelTypes.getFirst().id.equals(id + inlineSuffix + propertiesSuffix),
          "Long Properties ID clipped on Apply");
      save(out, prefix + "properties");
      historyRoundTrip(out, prefix + "properties");
    }
  }

  private static void historyRoundTrip(Path out, String prefix) throws Exception {
    var applied = ProjectIO.JSON.valueToTree(state.project());
    state.document.undo();
    state.revision++;
    state.refreshDraft();
    save(out, prefix + "-undone");
    state.document.redo();
    state.revision++;
    state.refreshDraft();
    save(out, prefix + "-redone");
    require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "Long ID Redo differed");
    frames(4);
  }

  private static void appendAt(float[] point, String text) {
    click(point);
    key(ImGuiKey.ModCtrl, true);
    key(ImGuiKey.End, true);
    key(ImGuiKey.End, false);
    key(ImGuiKey.ModCtrl, false);
    ImGui.getIO().addInputCharactersUTF8(text);
    frames(3);
  }

  private static void fixture(ProjectData p) {
    var stone = new VoxelType();
    stone.id = "stone";
    var snow = new VoxelType();
    snow.id = "snow";
    p.voxelTypes.addAll(List.of(stone, snow));
    for (int i = 0; i < 2048; i++) {
      var group = new Membership();
      group.id = "display-" + i;
      group.voxelTypeUuids.add(stone.uuid);
      p.voxelGroups.add(group);
    }
    var set = new Membership();
    set.id = "solid-set";
    set.voxelTypeUuids.addAll(List.of(stone.uuid, snow.uuid));
    p.voxelSets.add(set);
    var noise = new Noise();
    noise.id = "constant";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 1.);
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "ground";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = stone.uuid;
    var operation = new Operation();
    operation.id = "fill";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(stone.uuid));
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
  }

  private static void save(Path out, String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void click(float[] point) {
    require(point != null, "Native control absent");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void typeAt(float[] point, String text, boolean enter) {
    click(point);
    key(ImGuiKey.ModCtrl, true);
    key(ImGuiKey.A, true);
    key(ImGuiKey.ModCtrl, false);
    key(ImGuiKey.A, false);
    ImGui.getIO().addInputCharactersUTF8(text);
    frames(3);
    if (enter) {
      key(ImGuiKey.Enter, true);
      key(ImGuiKey.Enter, false);
      frames(3);
    }
  }

  private static void key(int key, boolean down) {
    ImGui.getIO().addKeyEvent(key, down);
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(2 * width + 64, height + 64);
    io.setFontGlobalScale(scale);
    ImGui.newFrame();
    ImGui.setWindowPos("Voxel Manager", 16, 16, ImGuiCond.Always);
    ImGui.setWindowSize("Voxel Manager", width, height, ImGuiCond.Always);
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    resources.draw();
    ImGui.setNextWindowPos(width + 32, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    properties.draw();
    find.draw(findOpen);
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
