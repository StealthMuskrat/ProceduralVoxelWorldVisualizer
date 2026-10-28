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
                    if (coordinates.x() == 999) throw new IllegalArgumentException();
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
        String oversized = "1".repeat(CoordinateText.MAX_CHARACTERS + 1);
        text("X", oversized);
        click("Apply");
        require(
            panel.pending() && panel.rawText("X").equals(oversized) && navigations[0] == 0,
            "Oversized displayed coordinates used an older private input");
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
        require(
            state.events.snapshot().stream()
                .anyMatch(
                    e ->
                        e.code().equals("BOOKMARK_NAVIGATION_FAILED")
                            && e.actionRequired()
                            && e.message().contains("IllegalArgumentException")),
            "Navigation failure lacks non-null actionable feedback");
        ownershipRecovery(out.resolve("ownership"));
        Files.writeString(
            out.resolve("bookmark-input-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("invalidTeleportRetained", true),
                    Map.entry("overflowBookmarkRetained", true),
                    Map.entry("tabOwned", true),
                    Map.entry("exactEndpoints", true),
                    Map.entry("callbackRetained", true),
                    Map.entry("oneUndoRedo", true),
                    Map.entry("navigationCount", navigations[0]),
                    Map.entry("displayedOverflowBlocked", true),
                    Map.entry("sameUuidReopenRejected", true),
                    Map.entry("renameSourcePreserved", true),
                    Map.entry("boundedRetryDetails", true),
                    Map.entry("runtimeDetailsCleared", true),
                    Map.entry("privateInputsPreserved", true),
                    Map.entry("externalCancelClosed", true),
                    Map.entry("pickerFailureRetained", true),
                    Map.entry("navigationErrorLogged", true))));
        System.out.println(
            "BOOKMARK_INPUT_NATIVE_PASS raw signs / overflow / tab ownership / exact endpoints /"
                + " callback correction / one Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static boolean popupOpen;
  private static boolean toolbar;

  private static void ownershipRecovery(Path out) throws Exception {
    Files.createDirectories(out);
    try (var state = new EditorState(new UserConfig(), false)) {
      require(
          state.change(
              p -> {
                var noise = new ProjectData.Noise();
                noise.id = "existing";
                p.noise2D.add(noise);
                var bookmark = new ProjectData.Bookmark();
                bookmark.name = "original";
                bookmark.x = 1;
                bookmark.y = 2;
                bookmark.z = 3;
                p.bookmarks.world.add(bookmark);
              }),
          "Ownership fixture rejected");
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (ProjectData.Noise) state.draft;
      draft.id = "pending-id";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(state.project());
      int[] navigations = {0};
      boolean[] rejectPicker = {false};
      panel =
          new BookmarkPanel(
              state,
              new BookmarkPanel.Navigation() {
                public Coordinates current(Tab tab) {
                  return new Coordinates(5, tab.threeDimensional() ? 6 : null, 7);
                }

                public void teleport(Tab tab, Coordinates coordinates) {
                  if (rejectPicker[0]) throw new IllegalStateException();
                  navigations[0]++;
                }
              });
      panel.openAdd(Tab.NOISE_2D, new Coordinates(-3, null, -7));
      frames(4);
      text("Name", "retained");
      var rejection =
          java.util.stream.IntStream.range(0, 300)
              .mapToObj(
                  i -> Diagnostic.error("BOOKMARK_FIXTURE", "/fixture/" + i, "detail".repeat(500)))
              .toList();
      state.document.setValidator(p -> rejection);
      for (int attempt = 0; attempt < 2; attempt++) {
        click("Apply");
        require(
            panel.pending() && panel.candidateIssueCount() == 300 && panel.diagnosticRows() == 8,
            "Repeated rejection lost current bounded details");
      }
      state.document.setValidator(
          p -> {
            throw new IllegalStateException();
          });
      click("Apply");
      require(
          panel.pending()
              && panel.candidateIssueCount() == 0
              && panel.diagnosticRows() == 0
              && panel.rawText("Name").equals("retained"),
          "Thrown validation reused earlier candidate details");
      require(
          before.equals(ProjectIO.JSON.valueToTree(state.project())),
          "Rejected bookmark changed project");
      state.document.setValidator(io.voxelcreator.core.NoiseCompiler::validate);
      click("Apply");
      frames(3);
      require(
          !panel.pending() && state.project().bookmarks.noise2D.size() == 1,
          "Bookmark retry did not close");
      var applied = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      state.revision++;
      require(
          before.equals(ProjectIO.JSON.valueToTree(state.project())),
          "Retry did not make one Undo");
      state.document.redo();
      state.revision++;
      require(
          applied.equals(ProjectIO.JSON.valueToTree(state.project())),
          "Retry Redo changed payload");
      require(
          state.draft == draft
              && draft.id.equals("pending-id")
              && state.seedInputs.text("seedOffset").equals("-"),
          "Bookmark retry consumed Properties input");

      Path file = out.resolve("owned-project.json");
      state.document.save(file);
      panel.openTeleport(Tab.WORLD);
      frames(4);
      text("X", "23");
      var projectId = state.project().project.uuid;
      require(
          state.document.open(file).isEmpty() && projectId.equals(state.project().project.uuid),
          "Same-UUID reopen fixture failed");
      frames(3);
      click("Apply");
      require(
          panel.pending() && panel.rawText("X").equals("23") && navigations[0] == 0,
          "Reopened project received an older navigation dialog");
      click("Cancel");

      var target = state.project().bookmarks.world.getFirst().uuid;
      panel.openRename(Tab.WORLD, target);
      frames(4);
      text("Name", "private-rename");
      require(
          state.change(p -> p.bookmarks.world.getFirst().name = "elsewhere"),
          "Changed-source fixture failed");
      frames(3);
      click("Apply");
      require(
          panel.pending()
              && panel.rawText("Name").equals("private-rename")
              && state.project().bookmarks.world.getFirst().name.equals("elsewhere"),
          "Stale Rename overwrote target");
      click("Cancel");
      toolbar = true;
      frames(3);
      rejectPicker[0] = true;
      click("picker");
      frames(3);
      click(panel.option(target));
      require(
          panel.selected(Tab.WORLD) == null && navigations[0] == 0,
          "Failed picker navigation changed selected bookmark");
      require(
          state.events.snapshot().stream()
              .anyMatch(e -> e.code().equals("BOOKMARK_NAVIGATION_FAILED") && e.actionRequired()),
          "Failed picker navigation did not report an actionable error");
      rejectPicker[0] = false;
      click(panel.option(target));
      require(
          target.equals(panel.selected(Tab.WORLD)) && navigations[0] == 1,
          "Picker retry did not retain its selectable row");
      panel.openTeleport(Tab.WORLD);
      frames(3);
      panel.cancel();
      frames(3);
      require(
          !panel.pending() && !popupOpen && navigations[0] == 1,
          "External Cancel retained modal ownership");
    } finally {
      toolbar = false;
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
    click(panel.control(key));
  }

  private static void click(float[] at) {
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
    if (toolbar) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(900, 600, ImGuiCond.Always);
      if (ImGui.begin("Bookmark owner toolbar")) panel.draw(Tab.WORLD);
      ImGui.end();
    }
    panel.drawDialogs();
    popupOpen = ImGui.isPopupOpen("Bookmark Coordinates");
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
