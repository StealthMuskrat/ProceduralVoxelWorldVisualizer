package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.model.ProjectData.Membership;
import java.util.*;

/**
 * Searchable multi-checkbox membership modal with a private selection and explicit Apply/Cancel.
 */
public final class MembershipDialog {
  private final EditorState state;
  final DialogLayout layout;
  private final MembershipEditSession session;
  private final ResourceTreeIndex.Cache cache = new ResourceTreeIndex.Cache();
  private final ResourceChoices choices = new ResourceChoices();
  private final ImString filter = new ImString(256);
  private boolean pending = true, selectedOnly;
  private String error = "";
  private List<ResourceTreeIndex.Node> displayed = List.of();
  private List<ResourceTreeIndex.Node> previous;
  private long selectedRevision = -1;
  private boolean previousSelectedOnly;
  private int submitted;
  final SettingsPageChrome chrome = new SettingsPageChrome(3, 3);
  private boolean issuesPage, revealPage;
  private float rowStride;
  private float rowLine, rowFrame, rowGap;
  private DialogBounds.Rect listBounds;
  private final Map<String, float[]> controls = new LinkedHashMap<>();
  private final Map<UUID, Integer> rowIds = new LinkedHashMap<>();

  public MembershipDialog(EditorState state, Membership draft) {
    this.state = state;
    layout = new DialogLayout(state, "Properties", 620, 510);
    session = new MembershipEditSession(state, draft);
  }

  public boolean draw() {
    controls.clear();
    rowIds.clear();
    submitted = 0;
    listBounds = null;
    if (pending) {
      layout.open("Edit Voxel Membership");
      pending = false;
    }
    if (!layout.begin("Edit Voxel Membership")) return false;
    boolean appearing = ImGui.isWindowAppearing();
    boolean current = session.currentOwner(state);
    chrome.begin("membership-body");
    if (revealPage) {
      ImGui.setScrollY(0);
      revealPage = false;
    }
    if (!issuesPage) {
      if (appearing) ImGui.setKeyboardFocusHere();
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      ImGui.inputTextWithHint("##membership-filter", "Filter voxel type IDs...", filter);
      remember("filter");
      var rows = choices.rows(cache.get(state.project(), state.revision), "voxel", filter.get());
      if (rows != previous
          || selectedRevision != session.revision()
          || selectedOnly != previousSelectedOnly) {
        displayed =
            selectedOnly ? rows.stream().filter(n -> session.contains(n.uuid())).toList() : rows;
        previous = rows;
        selectedRevision = session.revision();
        previousSelectedOnly = selectedOnly;
      }
      ImGui.textWrapped("Selected " + session.count() + "; rows " + displayed.size());
      if (!current) ImGui.textWrapped("! Membership owner changed. Cancel and reopen.");
      if (!error.isEmpty() || session.issueCount() != 0)
        ImGui.textWrapped("! Review Issues for membership feedback.");
      drawRows(current);
    } else {
      ImGui.textWrapped(
          "Apply Members commits only membership in one undoable edit. Any drafted resource ID"
              + " stays in Properties. Cancel discards this temporary selection.");
      if (!current) ImGui.textWrapped("! Membership owner changed. Cancel and reopen the editor.");
      if (!error.isEmpty()) ImGui.textWrapped("! " + StatusBarText.clip(error, 4096));
      ImGui.textWrapped("Rejected candidate issues: " + session.issueCount());
      for (var issue : session.applyIssues().stream().limit(8).toList())
        ImGui.textWrapped(StatusBarText.clip(issue.toString(), 1024));
      if (session.issueCount() > 8)
        ImGui.textWrapped(
            "Only the first eight candidate diagnostics are shown here. Review Validation or Logs"
                + " for more details.");
      if (error.isEmpty() && session.issueCount() == 0 && current)
        ImGui.textWrapped("No membership problems reported.");
    }
    chrome.end();
    boolean apply = action("apply", "Apply Members", current);
    boolean cancel = action("cancel", "Cancel", true);
    boolean page = action("section", issuesPage ? "Members" : "Issues", true);
    boolean select = action("select", "Select Matching", current && !issuesPage);
    boolean clear = action("clear", "Clear Matching", current && !issuesPage);
    boolean mode =
        action("selected-only", selectedOnly ? "Show All" : "Selected Only", !issuesPage);
    boolean done = false;
    if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.cancel();
      done = true;
      ImGui.closeCurrentPopup();
    } else if (select) session.setVisible(displayed, true);
    else if (clear) session.setVisible(displayed, false);
    else if (mode) selectedOnly = !selectedOnly;
    else if (page) {
      issuesPage = !issuesPage;
      revealPage = true;
    } else if (apply) {
      try {
        if (session.apply(state)) {
          done = true;
          ImGui.closeCurrentPopup();
        } else {
          error = "Membership was not applied. Review the diagnostics or Logs, then retry.";
          showIssues();
        }
      } catch (RuntimeException failure) {
        error = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
        showIssues();
      }
    }
    ImGui.endPopup();
    return done;
  }

  private void drawRows(boolean current) {
    boolean visible =
        UiScale.beginChild(
            "membership-options",
            0,
            ResourceReviewLayout.content(ImGui.getContentRegionAvailY()),
            true);
    listBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    rowLine = ImGui.getTextLineHeight();
    rowFrame = ImGui.getFrameHeight();
    rowGap = ImGui.getStyle().getItemSpacingY();
    rowStride =
        ResourceReviewLayout.membershipStride(
            ImGui.getFrameHeight(), ImGui.getStyle().getItemSpacingY());
    ImGui.beginDisabled(!current);
    if (visible) {
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(displayed.size(), rowStride);
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var node = displayed.get(i);
            float top = ImGui.getCursorPosY();
            submitted++;
            var selected = new ImBoolean(session.contains(node.uuid()));
            String label =
                AppMetadata.uiText(StatusBarText.clip(node.id(), 160)) + "###" + node.uuid();
            if (ImGui.checkbox(label, selected)) session.set(node.uuid(), selected.get());
            rowIds.put(node.uuid(), ImGui.getID(label));
            remember(node.uuid().toString());
            if (ImGui.isItemHovered())
              UiScale.tooltip(StatusBarText.clip(node.id(), 1024) + "\nUUID: " + node.uuid());
            ImGui.setCursorPosY(top + rowStride - ImGui.getStyle().getItemSpacingY());
            ImGui.dummy(0, 0);
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
    }
    ImGui.endDisabled();
    ImGui.endChild();
  }

  private void showIssues() {
    issuesPage = true;
    revealPage = true;
  }

  private boolean action(String key, String label, boolean enabled) {
    ImGui.beginDisabled(!enabled);
    boolean clicked = chrome.identifiedAction(key, label);
    ImGui.endDisabled();
    remember(key);
    return enabled && clicked;
  }

  private void remember(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  int submitted() {
    return submitted;
  }

  String section() {
    return issuesPage ? "Issues" : "Members";
  }

  Map<String, Float> rowMetrics() {
    return Map.of("line", rowLine, "frame", rowFrame, "gap", rowGap);
  }

  float rowStride() {
    return rowStride;
  }

  DialogBounds.Rect listBounds() {
    return listBounds;
  }

  Map<UUID, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  float[] control(String key) {
    return controls.get(key);
  }

  MembershipEditSession session() {
    return session;
  }
}
