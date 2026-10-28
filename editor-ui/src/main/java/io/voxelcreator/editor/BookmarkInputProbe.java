package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production bookmark/teleport text, correction and callback failure workflow. */
public final class BookmarkInputProbe {
  private static BookmarkPanel panel;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        var current = new EnumMap<Tab, Coordinates>(Tab.class);
        current.put(Tab.NOISE_2D, new Coordinates(-3, null, -7));
        current.put(Tab.WORLD, new Coordinates(1, 2, 3));
        int[] navigations = {0};
        panel =
            new BookmarkPanel(
                state,
                new BookmarkPanel.Navigation() {
                  public Coordinates current(Tab tab) {
                    return current.get(tab);
                  }

                  public void teleport(Tab tab, Coordinates coordinates) {
                    if (coordinates.x() == 999)
                      throw new IllegalArgumentException("Injected navigation rejection");
                    navigations[0]++;
                    current.put(tab, coordinates);
                  }
                });
        var before = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        panel.openTeleport(Tab.WORLD);
        frames(4);
        text("X", "-");
        click("Apply");
        require(
            panel.pending()
                && navigations[0] == 0
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Invalid teleport used previous X");
        panel.openAdd(Tab.NOISE_2D, null);
        text("X", "-2147483648");
        text("Y", "2147483647");
        text("Z", "-7");
        click("Apply");
        require(
            !panel.pending()
                && navigations[0] == 1
                && current
                    .get(Tab.WORLD)
                    .equals(new Coordinates(Integer.MIN_VALUE, Integer.MAX_VALUE, -7)),
            "Tab owner or integer endpoints changed");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.document.historyBytes() == 0,
            "Teleport dirtied project");
        panel.openAdd(Tab.NOISE_2D, null);
        frames(4);
        text("Name", "exact");
        text("X", "2147483648");
        click("Apply");
        require(
            panel.pending()
                && state.project().bookmarks.noise2D.isEmpty()
                && state.document.historyBytes() == 0,
            "Overflow bookmark published");
        text("X", "17");
        click("Apply");
        require(
            !panel.pending() && state.project().bookmarks.noise2D.size() == 1,
            "Bookmark repair failed");
        var bookmark = state.project().bookmarks.noise2D.getFirst();
        require(
            bookmark.name.equals("exact")
                && bookmark.x == 17
                && bookmark.y == null
                && bookmark.z == -7,
            "2D coordinate shape");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One bookmark Undo");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One bookmark Redo");
        panel.openTeleport(Tab.WORLD);
        frames(4);
        text("X", "999");
        click("Apply");
        require(
            panel.pending() && navigations[0] == 1, "Navigation callback failure closed dialog");
        text("X", "23");
        click("Apply");
        require(
            !panel.pending()
                && navigations[0] == 2
                && current.get(Tab.WORLD).x() == 23
                && applied.equals(ProjectIO.JSON.valueToTree(state.project()))
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Callback repair or preference isolation");
        state.document.save(out.resolve("bookmark-input-project.json"));
        Files.writeString(
            out.resolve("bookmark-input-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "invalidTeleportRetained",
                    true,
                    "overflowBookmarkRetained",
                    true,
                    "tabOwned",
                    true,
                    "exactEndpoints",
                    true,
                    "callbackRetained",
                    true,
                    "oneUndoRedo",
                    true,
                    "navigationCount",
                    navigations[0])));
        System.out.println(
            "BOOKMARK_INPUT_NATIVE_PASS raw signs / overflow / tab ownership / exact endpoints /"
                + " callback correction / one Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void text(String key, String value) {
    click(key);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
  }

  private static void click(String key) {
    var at = panel.control(key);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    panel.drawDialogs();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
