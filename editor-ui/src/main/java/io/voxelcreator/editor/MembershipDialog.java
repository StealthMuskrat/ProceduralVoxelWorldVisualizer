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
    session = new MembershipEditSession(state, draft);
  }

  public boolean draw() {
    if (pending) {
      ImGui.openPopup("Edit Voxel Membership");
      pending = false;
    }
    ImGui.setNextWindowSize(620, 510, ImGuiCond.FirstUseEver);
    if (!ImGui.beginPopupModal("Edit Voxel Membership")) return false;
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
    if (ImGui.button("Select Matching")) session.setVisible(displayed, true);
    ImGui.sameLine();
    if (ImGui.button("Clear Matching")) session.setVisible(displayed, false);
    ImGui.beginChild(
        "membership-options", 0, Math.max(100, ImGui.getContentRegionAvailY() - 85), true);
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
    if (!error.isEmpty()) ImGui.textWrapped("! " + error);
    if (ImGui.button("Apply Members")) {
      try {
        if (session.apply(state)) {
          done = true;
          ImGui.closeCurrentPopup();
        }
      } catch (RuntimeException e) {
        error = e.getMessage();
      }
    }
    ImGui.sameLine();
    if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
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
