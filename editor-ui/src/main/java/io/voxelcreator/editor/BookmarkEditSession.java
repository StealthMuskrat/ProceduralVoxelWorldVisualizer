package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Dialog ownership survives unrelated edits, but never a replacement document or changed target.
 */
final class BookmarkEditSession {
  static final int MAX_NAME_CHARACTERS = 4096;
  private static final ProjectChangeResult NO_RESULT =
      new ProjectChangeResult(false, 0, List.of(), "");
  private final EditorState state;
  private final Object documentToken;
  private final UUID project, world;
  private final Tab tab;
  private final Draft draft;
  private final JsonNode targetBase;
  private final CoordinateText input;
  private String name;
  private final int nameLimit;
  private boolean closed, applying;
  private ProjectChangeResult result = NO_RESULT;

  private BookmarkEditSession(EditorState state, Tab tab, Draft draft, Coordinates coordinates) {
    this.state = Objects.requireNonNull(state);
    this.tab = Objects.requireNonNull(tab);
    this.draft = draft;
    documentToken = state.document.sessionToken();
    project = state.project().project.uuid;
    world = state.project().worldGenerator.uuid;
    name = draft == null ? "" : draft.name();
    nameLimit = Math.max(MAX_NAME_CHARACTERS, name.length());
    targetBase =
        draft == null || draft.adding()
            ? null
            : ProjectIO.JSON.valueToTree(
                BookmarkTransactions.find(state.project(), tab, draft.uuid()));
    input = targetBase == null ? new CoordinateText(tab, coordinates) : null;
  }

  static BookmarkEditSession add(EditorState state, Tab tab, Coordinates coordinates) {
    return new BookmarkEditSession(
        state, tab, Draft.add(state.project(), tab, coordinates), coordinates);
  }

  static BookmarkEditSession rename(EditorState state, Tab tab, UUID id) {
    var draft = Draft.rename(state.project(), tab, id);
    return new BookmarkEditSession(state, tab, draft, draft.coordinates());
  }

  static BookmarkEditSession teleport(EditorState state, Tab tab, Coordinates coordinates) {
    return new BookmarkEditSession(state, tab, null, coordinates);
  }

  String title() {
    return draft == null
        ? "Teleport Coordinates"
        : draft.adding() ? "Add Bookmark" : "Rename Bookmark";
  }

  Tab tab() {
    return tab;
  }

  String name() {
    return name;
  }

  int nameLimit() {
    return nameLimit;
  }

  UUID uuid() {
    return draft == null ? null : draft.uuid();
  }

  boolean named() {
    return draft != null;
  }

  boolean closed() {
    return closed;
  }

  List<String> axes() {
    return input == null ? List.of() : input.axes();
  }

  /** Correction target only; Apply still validates complete displayed input. */
  String problemField() {
    if (named() && name.isBlank()) return "Name";
    return input == null ? null : input.problemAxis();
  }

  String text(String axis) {
    if (input == null) throw new IllegalStateException("Rename has no editable coordinate fields");
    return input.text(axis);
  }

  Coordinates renamedCoordinates() {
    return draft.coordinates();
  }

  ProjectChangeResult result() {
    return result;
  }

  void editName(String value) {
    requireOpen();
    if (applying) throw new IllegalStateException("Bookmark action is already running");
    if (draft == null) throw new IllegalStateException("Teleport has no name field");
    if (Objects.requireNonNull(value).length() > nameLimit)
      throw new IllegalArgumentException("Bookmark name text limit " + nameLimit);
    if (!name.equals(value)) {
      name = value;
      result = NO_RESULT;
    }
  }

  void edit(String axis, String value) {
    requireOpen();
    if (applying) throw new IllegalStateException("Bookmark action is already running");
    String previous = text(axis);
    input.edit(axis, value);
    if (!previous.equals(value)) result = NO_RESULT;
  }

  boolean current() {
    if (closed
        || documentToken != state.document.sessionToken()
        || !Objects.equals(project, state.project().project.uuid)
        || !Objects.equals(world, state.project().worldGenerator.uuid)) return false;
    if (targetBase != null) {
      var target = BookmarkTransactions.find(state.project(), tab, draft.uuid());
      return target != null && targetBase.equals(ProjectIO.JSON.valueToTree(target));
    }
    return true;
  }

  boolean apply(
      String displayedName, Map<String, String> displayed, BiConsumer<Tab, Coordinates> navigate) {
    requireOpen();
    if (applying) throw new IllegalStateException("Bookmark action is already running");
    if (!current())
      throw new IllegalStateException(
          "Document or bookmark changed; Cancel and reopen this dialog");
    if (draft != null && !Objects.equals(name, displayedName))
      throw new IllegalStateException("Displayed name differs from the private bookmark input");
    if (draft == null && displayedName != null)
      throw new IllegalStateException("Teleport has no name field");
    Coordinates coordinates = null;
    if (input != null) coordinates = input.parse(displayed);
    else if (!displayed.isEmpty())
      throw new IllegalStateException("Rename changes only the bookmark name");
    if (draft == null) Objects.requireNonNull(navigate);
    applying = true;
    try {
      if (draft == null) {
        try {
          navigate.accept(tab, coordinates);
        } catch (RuntimeException failure) {
          result = ProjectChangeResult.failed(failure);
          state.error(
              "BOOKMARK_NAVIGATION_FAILED", EditorEvents.Subsystem.APPLICATION, result.failure());
          throw failure;
        }
        result = NO_RESULT;
        closed = true;
        return true;
      }
      draft.name(name);
      if (draft.adding()) draft.coordinates(coordinates);
      draft.checkCurrent(state.project());
      result = state.changeResult(draft::applyTo);
      if (result.applied()) closed = true;
      return result.applied();
    } finally {
      applying = false;
    }
  }

  void cancel() {
    closed = true;
    result = NO_RESULT;
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Bookmark dialog is closed");
  }
}
