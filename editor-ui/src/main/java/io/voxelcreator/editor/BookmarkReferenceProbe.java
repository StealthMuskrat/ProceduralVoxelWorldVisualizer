package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Explicit isolated native bookmark workflow, including actual mouse, text and Escape events. */
public final class BookmarkReferenceProbe {
  private static Tab active = Tab.NOISE_2D;
  private static BookmarkPanel panel;

  private static final class Navigation implements BookmarkPanel.Navigation {
    final Map<Tab, Coordinates> centers = new EnumMap<>(Tab.class);
    Tab last;
    int calls;
    Navigation() {
      centers.put(Tab.NOISE_2D, new Coordinates(-3, null, -7));
      centers.put(Tab.NOISE_3D, new Coordinates(-11, 13, -17));
      centers.put(Tab.WORLD, new Coordinates(19, -23, 29));
    }
    public Coordinates current(Tab tab) { return centers.get(tab); }
    public Coordinates picked(Tab tab) {
      return tab == Tab.NOISE_2D ? new Coordinates(-101, null, -103) : null;
    }
    public void teleport(Tab tab, Coordinates coordinates) {
      centers.put(tab, coordinates); last = tab; calls++;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the isolated output directory");
    var output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null); io.setDisplaySize(1400, 900); io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault(); io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(p -> {
          for (int i = 0; i < 2000; i++) bookmark(p, Tab.NOISE_2D,
              String.format(Locale.ROOT, "marker-%05d", i), i, 0, -i);
          bookmark(p, Tab.NOISE_3D, "duplicate", -11, 13, -17);
          bookmark(p, Tab.WORLD, "duplicate", 1, 2, 3);
          bookmark(p, Tab.WORLD, "duplicate", -5, -7, -9);
          var n = new Noise(); n.id = "bookmark-field"; n.algorithm.key = "CONSTANT";
          n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .625); p.noise2D.add(n);
        }), "Fixture validation");
        var field = state.project().noise2D.getFirst().uuid;
        state.select("noise2D", field);
        ((Noise) state.draft).id = "pending-id";
        Object propertyDraft = state.draft;
        state.document.save(output.resolve("baseline.json"));
        var before = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes(), revision = state.revision;
        String computation = PreviewIdentity.of(state.project(), 2, null);
        var navigation = new Navigation();
        panel = new BookmarkPanel(state, navigation);
        frames(3);
        click(panel.control("picker")); frames(3);
        int unfiltered = panel.submitted();
        require(unfiltered > 0 && unfiltered <= 30, "Unbounded bookmark selector");
        int builds = panel.builds();
        io.addInputCharactersUTF8("marker-01999"); frames(3);
        require(panel.submitted() == 1 && panel.builds() == builds, "Filter rebuilt the catalog");
        UUID last = state.project().bookmarks.noise2D.getLast().uuid;
        click(panel.option(last)); frames(2);
        require(last.equals(panel.selected(Tab.NOISE_2D)) && navigation.calls == 1,
            "Actual picker click did not select exactly once");
        require(navigation.centers.get(Tab.NOISE_2D).equals(new Coordinates(1999, null, -1999)), "2D teleport");
        require(before.equals(ProjectIO.JSON.valueToTree(state.project()))
            && history == state.document.historyBytes() && revision == state.revision
            && !state.document.dirty() && state.draft == propertyDraft, "Navigation changed project or draft");

        active = Tab.WORLD; frames(3);
        click(panel.control("picker")); frames(3);
        io.addInputCharactersUTF8("duplicate"); frames(3);
        require(panel.submitted() == 2, "Duplicate names must remain distinct rows");
        UUID target = state.project().bookmarks.world.getLast().uuid;
        click(panel.option(target)); frames(2);
        require(target.equals(panel.selected(Tab.WORLD)), "Duplicate-name UUID selection");
        require(navigation.centers.get(Tab.WORLD).equals(new Coordinates(-5, -7, -9)), "World coordinates");
        click(panel.control("Rename")); frames(3);
        io.addInputCharactersUTF8("renamed"); frames(3);
        click(panel.control("Apply")); frames(2);
        require(BookmarkTransactions.find(state.project(), Tab.WORLD, target).name.equals("renamed"), "Native Rename");
        state.document.undo(); state.revision++; frames(2);
        require(BookmarkTransactions.find(state.project(), Tab.WORLD, target).name.equals("duplicate"), "Rename Undo");
        state.document.redo(); state.revision++; frames(2);
        navigation.centers.put(Tab.WORLD, new Coordinates(-31, -37, -41));
        click(panel.control("Update Coordinates")); frames(2);
        require(BookmarkTransactions.coordinates(BookmarkTransactions.find(state.project(), Tab.WORLD, target))
            .equals(new Coordinates(-31, -37, -41)), "Native coordinate update");
        click(panel.control("Delete Bookmark")); frames(2);
        require(BookmarkTransactions.find(state.project(), Tab.WORLD, target) == null, "Native delete");
        state.document.undo(); state.revision++; frames(2);
        require(BookmarkTransactions.find(state.project(), Tab.WORLD, target) != null, "Delete Undo");

        active = Tab.NOISE_2D; frames(2);
        click(panel.control("Add Bookmark")); frames(3);
        active = Tab.WORLD; frames(2); // A dialog must retain its original tab.
        io.addInputCharactersUTF8("picked-point"); frames(3);
        click(panel.control("Apply")); frames(2);
        var added = state.project().bookmarks.noise2D.getLast();
        require(added.name.equals("picked-point") && added.x == -101 && added.y == null && added.z == -103,
            "Picked coordinate or owning tab changed in Add dialog");
        require(state.project().bookmarks.world.size() == 2, "Add leaked across tabs");
        state.document.undo(); state.revision++; frames(2);
        require(state.project().bookmarks.noise2D.size() == 2000, "Add must be one Undo action");
        state.document.redo(); state.revision++; frames(2);

        var afterEdits = ProjectIO.JSON.valueToTree(state.project());
        long editHistory = state.document.historyBytes(), editRevision = state.revision;
        int calls = navigation.calls;
        click(panel.control("Teleport")); frames(3);
        io.addKeyEvent(ImGuiKey.Escape, true); frame();
        io.addKeyEvent(ImGuiKey.Escape, false); frames(2);
        require(navigation.calls == calls, "Escape teleported");
        active = Tab.NOISE_3D; frames(2);
        click(panel.control("Teleport")); frames(3);
        active = Tab.WORLD; frames(2);
        click(panel.control("Apply")); frames(2);
        require(navigation.last == Tab.NOISE_3D && navigation.calls == calls + 1, "Teleport owner changed");
        require(afterEdits.equals(ProjectIO.JSON.valueToTree(state.project()))
            && editHistory == state.document.historyBytes() && editRevision == state.revision
            && state.draft == propertyDraft && field.equals(state.selected), "Teleport changed authoring state");
        require(computation.equals(PreviewIdentity.of(state.project(), 2, null))
            && new NoiseCompiler(state.project(), 0).compile(field).sample(-3, 0, -7) == .625,
            "Bookmark edits changed computation");
        state.document.save(output.resolve("bookmarks-project.json"));
        var reload = new ProjectIO().load(output.resolve("bookmarks-project.json"));
        require(reload.success() && ProjectIO.JSON.valueToTree(reload.project()).equals(ProjectIO.JSON.valueToTree(state.project())),
            "Bookmark save/reopen");
        Files.writeString(output.resolve("bookmarks-reference.json"), ProjectIO.JSON.writeValueAsString(Map.of(
            "formatVersion", 1, "catalog", 2000, "submittedUnfiltered", unfiltered,
            "filtered", 1, "duplicateRows", 2, "navigationCalls", navigation.calls,
            "owner", navigation.last.key(), "field", .625)));
        System.out.println("BOOKMARK_WORKFLOW_NATIVE_PASS clipped searchable selector / duplicate UUID rows / actual mouse and text"
            + " / picked Add / Rename / Update / Delete / Undo-Redo / tab-owned dialogs / Escape Cancel"
            + " / navigation and generation invariance / save-reopen");
      }
    } finally { ImGui.destroyContext(); }
  }

  private static void bookmark(ProjectData project, Tab tab, String name, int x, int y, int z) {
    var b = new Bookmark(); b.name = name; b.x = x; b.y = tab.threeDimensional() ? y : null; b.z = z;
    BookmarkTransactions.list(project, tab).add(b);
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always); ImGui.setNextWindowSize(950, 650, ImGuiCond.Always);
    ImGui.begin("Bookmark Workflow Reference"); panel.draw(active); ImGui.end();
    panel.drawDialogs(); ImGui.render();
  }
  private static void click(float[] center) {
    var io = ImGui.getIO(); io.addMousePosEvent(center[0], center[1]); frame();
    io.addMouseButtonEvent(0, true); frame(); io.addMouseButtonEvent(0, false); frame();
  }
}
