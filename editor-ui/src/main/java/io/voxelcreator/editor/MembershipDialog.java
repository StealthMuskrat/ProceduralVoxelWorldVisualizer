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

  public MembershipDialog(EditorState state, Membership draft) {
    this.state = state;
    layout = new DialogLayout(state, "Properties", 620, 510);
    session = new MembershipEditSession(state, draft);
  }

  public boolean draw() {
    if (pending) {
      layout.open("Edit Voxel Membership");
      pending = false;
    }
    if (!layout.begin("Edit Voxel Membership")) return false;
    boolean done = false;
    ImGui.textWrapped(
        "Apply Members commits only membership in one undoable edit. Any drafted resource ID stays"
            + " in Properties. Cancel discards this temporary selection.");
    if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
    ImGui.inputTextWithHint("##membership-filter", "Filter voxel type IDs...", filter);
    var check = new ImBoolean(selectedOnly);
    if (ImGui.checkbox("Selected only", check)) selectedOnly = check.get();
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
    ImGui.text(session.count() + " selected / " + displayed.size() + " matching");
    if (DialogLayout.button("Select Matching")) session.setVisible(displayed, true);
    DialogLayout.sameLine("Clear Matching");
    if (DialogLayout.button("Clear Matching")) session.setVisible(displayed, false);
    boolean current = session.currentOwner(state);
    float footer =
        85
            + (current ? 0 : 40)
            + (error.isEmpty() ? 0 : 40)
            + (session.issueCount() == 0 ? 0 : 130);
    UiScale.beginChild(
        "membership-options", 0, Math.max(100, ImGui.getContentRegionAvailY() - footer), true);
    submitted = 0;
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(displayed.size(), ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var node = displayed.get(i);
          submitted++;
          var selected = new ImBoolean(session.contains(node.uuid()));
          if (ImGui.checkbox(AppMetadata.uiText(node.id()) + "###" + node.uuid(), selected))
            session.set(node.uuid(), selected.get());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (!current) ImGui.textWrapped("! Membership owner changed; Cancel and reopen the editor.");
    if (!error.isEmpty()) ImGui.textWrapped("! " + error);
    if (session.issueCount() != 0) {
      UiScale.beginChild("membership-diagnostics", 0, 110, true);
      for (var issue : session.applyIssues().stream().limit(8).toList()) {
        var detail = issue.toString();
        ImGui.textWrapped(detail.length() > 1024 ? detail.substring(0, 1023) + "…" : detail);
      }
      ImGui.endChild();
    }
    if (session.issueCount() > 8)
      ImGui.textDisabled("Only the first eight candidate diagnostics are shown here.");
    ImGui.beginDisabled(!current);
    if (DialogLayout.button("Apply Members")) {
      try {
        if (session.apply(state)) {
          done = true;
          ImGui.closeCurrentPopup();
        } else error = "Membership was not applied. Review the diagnostics or Logs, then retry.";
      } catch (RuntimeException e) {
        error = Objects.requireNonNullElse(e.getMessage(), e.toString());
      }
    }
    ImGui.endDisabled();
    DialogLayout.sameLine("Cancel");
    if (DialogLayout.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.cancel();
      done = true;
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
    return done;
  }

  int submitted() {
    return submitted;
  }
}
