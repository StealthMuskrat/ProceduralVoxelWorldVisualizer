package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import java.util.*;

/** Tab-owned bookmark toolbar, clipped selector and private Add/Rename/Teleport dialogs. */
public final class BookmarkPanel {
  public interface Navigation {
    Coordinates current(Tab tab);

    default Coordinates picked(Tab tab) {
      return null;
    }

    void teleport(Tab tab, Coordinates coordinates);
  }

  private final EditorState state;
  final DialogLayout layout;
  final SettingsPageChrome chrome = new SettingsPageChrome(2, 2);
  final SettingsActionScope actionScope = new SettingsActionScope();
  private final Map<String, Integer> fieldIds = new LinkedHashMap<>();
  private boolean revealBody, revealFeedback;
  private String revealField;
  final PopupLayout choicesLayout = new PopupLayout();
  private final Navigation navigation;
  private final BookmarkIndex.Cache cache = new BookmarkIndex.Cache();
  private final Map<Tab, UUID> selected = new EnumMap<>(Tab.class);
  private final Map<Tab, ImString> filters = new EnumMap<>(Tab.class);
  private final Map<UUID, float[]> options = new HashMap<>();
  private final Map<String, float[]> controls = new HashMap<>();
  private ImString name = new ImString(4 * BookmarkEditSession.MAX_NAME_CHARACTERS + 1);
  private BookmarkEditSession session;
  private Object observedDocument;
  private final Map<String, ImString> coordinateBuffers = new LinkedHashMap<>();
  private String error = "";
  private boolean pending;
  private boolean closing;
  private int submitted;
  private int diagnosticRows;

  public BookmarkPanel(EditorState state, Navigation navigation) {
    this.state = Objects.requireNonNull(state);
    layout = new DialogLayout(state, "Visualizers", 700, 520);
    this.navigation = Objects.requireNonNull(navigation);
    observedDocument = state.document.sessionToken();
    for (Tab tab : Tab.values()) filters.put(tab, new ImString(256));
  }

  private static final class Toolbar {
    final SettingsActionScope scope = new SettingsActionScope();
    BookmarkActionContext context;
    int frame = -1;
    final Map<String, Integer> ids = new LinkedHashMap<>();
    final Map<String, DialogBounds.Rect> bounds = new LinkedHashMap<>();
  }

  private record Choice(Tab tab, BookmarkIndex.Row row, SelectorSession.Ticket ticket) {}

  private final Map<Tab, Toolbar> toolbars = new EnumMap<>(Tab.class);
  private final Map<Tab, SelectorSession> choiceSessions = new EnumMap<>(Tab.class);
  private final String nativeId = UUID.randomUUID().toString();
  private long choiceOpenings;
  private final Map<String, Integer> choiceIds = new LinkedHashMap<>();
  private Tab probeOpen;
  private float choiceStride;

  private Toolbar toolbar(Tab tab) {
    return toolbars.computeIfAbsent(tab, key -> new Toolbar());
  }

  private SelectorSession choices(Tab tab) {
    return choiceSessions.computeIfAbsent(tab, key -> new SelectorSession());
  }

  private void synchronize() {
    if (state.document.sessionToken() != observedDocument) {
      selected.clear();
      filters.values().forEach(filter -> filter.set(""));
      choiceSessions.values().forEach(SelectorSession::retire);
      toolbars
          .values()
          .forEach(
              toolbar -> {
                toolbar.scope.release();
                toolbar.context = null;
              });
      observedDocument = state.document.sessionToken();
    }
  }

  private BookmarkActionContext context(Tab tab) {
    return BookmarkActionContext.capture(
        state,
        tab,
        selected.get(tab),
        cache.get(state.project(), state.revision, tab).get(selected.get(tab)),
        session,
        pending());
  }

  private boolean current(BookmarkActionContext context) {
    return context.current(state, context.tab(), selected.get(context.tab()), session, pending());
  }

  private void requireCurrent(BookmarkActionContext context) {
    if (!current(context))
      throw new IllegalStateException(
          "Document or bookmark context changed during coordinate capture; retry.");
  }

  private SelectorSession.Context choiceContext(Tab tab, BookmarkIndex index) {
    return new SelectorSession.Context(
        state.document.sessionToken(),
        state.project(),
        state.revision,
        session == null ? tab : session,
        pending() ? 1 : 0,
        selected.get(tab),
        tab.key(),
        null,
        index,
        filters.get(tab).get());
  }

  public void draw(Tab tab) {
    synchronize();
    var index = cache.get(state.project(), state.revision, tab);
    var current = index.get(selected.get(tab));
    var review = context(tab);
    var toolbar = toolbar(tab);
    int frame = ImGui.getFrameCount();
    if (frame < toolbar.frame || frame - toolbar.frame > 1) toolbar.scope.release();
    toolbar.frame = frame;
    if (!review.same(toolbar.context)) toolbar.context = review;
    long version = toolbar.scope.synchronize(review.lifetime(), toolbar.context);
    toolbar.ids.clear();
    toolbar.bounds.clear();
    ImGui.pushID("bookmarks-" + tab.key());
    String scope = "bookmark-toolbar:" + nativeId + ":" + version;
    ImGui.pushID(scope);
    String preview = current == null ? "Choose coordinates..." : current.label();
    String widget = AppMetadata.uiText(preview) + "  v###bookmark-picker";
    toolbar.ids.put("picker", ImGui.getID(widget));
    boolean picker =
        ImGui.button(widget, Math.max(1, Math.min(360, ImGui.getContentRegionAvailX())), 0);
    var anchor = PopupLayout.itemBounds();
    toolbar.bounds.put("picker", anchor);
    if (ImGui.isItemHovered()) UiScale.tooltip(preview);
    record("picker");
    ImGui.popID();
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false);
    var choiceSession = choices(tab);
    boolean retrying = false;
    if ((picker || probeOpen == tab) && !escape && current(review)) {
      choiceSession.open();
      choiceOpenings++;
      ImGui.openPopup("bookmark-choice");
    } else if (!escape && choiceSession.retry(choiceContext(tab, index), frame)) {
      choiceOpenings++;
      retrying = true;
      ImGui.openPopup("bookmark-choice");
    }
    if (probeOpen == tab) probeOpen = null;
    submitted = 0;
    options.clear();
    choiceIds.clear();
    Choice chosen = null;
    if (choicesLayout.begin("bookmark-choice", anchor, 480, 340)) {
      var ticket = choiceSession.observe(frame, choiceContext(tab, index));
      if (escape || ticket == null) {
        choiceSession.retire();
        ImGui.closeCurrentPopup();
      } else chosen = drawChoices(tab, index, ticket, retrying);
      ImGui.endPopup();
    } else if (choiceSession.active()) choiceSession.retire();
    ImGui.pushID(scope);
    boolean add = button(toolbar, "Add Bookmark");
    DialogLayout.sameLine("Teleport");
    boolean teleport = button(toolbar, "Teleport");
    ImGui.beginDisabled(current == null || pending());
    boolean rename = button(toolbar, "Rename");
    DialogLayout.sameLine("Update Coordinates");
    boolean update = button(toolbar, "Update Coordinates");
    DialogLayout.sameLine("Delete Bookmark");
    boolean delete = button(toolbar, "Delete Bookmark");
    ImGui.endDisabled();
    ImGui.popID();
    ImGui.popID();
    if (escape) return;
    if (chosen != null) {
      navigate(chosen, index);
      return;
    }
    if (!current(review) || !toolbar.scope.current(state.document.sessionToken(), toolbar.context))
      return;
    if (add) {
      try {
        var picked = navigation.picked(tab);
        requireCurrent(review);
        openAdd(tab, picked);
      } catch (RuntimeException failure) {
        state.error(
            "BOOKMARK_DIALOG_FAILED",
            EditorEvents.Subsystem.APPLICATION,
            bounded(message(failure)));
      }
    } else if (teleport) openTeleport(tab);
    else if (rename && current != null && !pending()) openRename(tab, current.uuid());
    else if (update && current != null && !pending()) {
      try {
        var captured = navigation.current(tab);
        requireCurrent(review);
        state.change(p -> BookmarkTransactions.update(p, tab, current.uuid(), captured));
      } catch (RuntimeException failure) {
        state.error(
            "BOOKMARK_UPDATE_FAILED",
            EditorEvents.Subsystem.APPLICATION,
            bounded(message(failure)));
      }
    } else if (delete && current != null && !pending()) {
      if (state.change(p -> BookmarkTransactions.delete(p, tab, current.uuid()))
          && currentSelection(tab, current.uuid())) selected.remove(tab);
    }
  }

  private boolean currentSelection(Tab tab, UUID id) {
    return Objects.equals(selected.get(tab), id);
  }

  private Choice drawChoices(
      Tab tab, BookmarkIndex index, SelectorSession.Ticket ticket, boolean retrying) {
    var owner = choices(tab);
    if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
    choicesLayout.search("##bookmark-query", "Filter names or coordinates...", filters.get(tab));
    ticket = owner.observe(ImGui.getFrameCount(), choiceContext(tab, index));
    if (ticket == null) {
      ImGui.closeCurrentPopup();
      return null;
    }
    var rows = index.rows(filters.get(tab).get());
    ImGui.textWrapped(rows.size() + " found");
    UiScale.beginChild("bookmark-options", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
    choicesLayout.observeList();
    if (retrying) ImGui.setScrollY(0);
    if (!owner.feedback().isEmpty()) ImGui.textWrapped("! " + owner.feedback());
    choiceStride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    ImGui.pushID("bookmark-rows:" + nativeId + ":" + tab.key() + ":" + ticket.version());
    ImGui.beginDisabled(pending());
    var clipper = new ImGuiListClipper();
    BookmarkIndex.Row chosen = null;
    try {
      clipper.begin(rows.size(), choiceStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          submitted++;
          float top = ImGui.getCursorPosY();
          String label = AppMetadata.uiText(row.label()) + "###" + row.uuid();
          choiceIds.put(row.uuid().toString(), ImGui.getID(label));
          if (ImGui.selectable(
                  label,
                  row.uuid().equals(selected.get(tab)),
                  0,
                  Math.max(1, ImGui.getContentRegionAvailX()),
                  ImGui.getTextLineHeight())
              && chosen == null) chosen = row;
          PopupLayout.clippedLabel(row.label() + " / " + row.uuid());
          options.put(row.uuid(), bounds());
          ImGui.setCursorPosY(top + choiceStride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endDisabled();
    ImGui.popID();
    ImGui.endChild();
    if (chosen != null
        && !pending()
        && BookmarkActionContext.rowCurrent(state, tab, chosen)
        && owner.consume(ticket, ImGui.getFrameCount(), choiceContext(tab, index))) {
      ImGui.closeCurrentPopup();
      return new Choice(tab, chosen, ticket);
    }
    return null;
  }

  private void navigate(Choice choice, BookmarkIndex index) {
    var owner = choices(choice.tab());
    long completion = choiceOpenings;
    try {
      navigation.teleport(choice.tab(), choice.row().coordinates());
      if (choice.ticket().context().same(choiceContext(choice.tab(), index))
          && BookmarkActionContext.rowCurrent(state, choice.tab(), choice.row()))
        selected.put(choice.tab(), choice.row().uuid());
      owner.complete(choice.ticket());
    } catch (RuntimeException failure) {
      String detail = bounded(message(failure));
      state.error("BOOKMARK_NAVIGATION_FAILED", EditorEvents.Subsystem.APPLICATION, detail);
      if (completion == choiceOpenings) owner.failed(choice.ticket(), detail);
      else owner.complete(choice.ticket());
    } finally {
      synchronize();
    }
  }

  private boolean button(Toolbar toolbar, String label) {
    toolbar.ids.put(label, ImGui.getID(label));
    boolean clicked = DialogLayout.button(label);
    toolbar.bounds.put(label, PopupLayout.itemBounds());
    record(label);
    return clicked;
  }

  public void openAdd(Tab tab, Coordinates picked) {
    if (pending()) {
      state.status = "Finish the open bookmark dialog first";
      return;
    }
    try {
      var review = context(tab);
      var captured = picked == null ? navigation.current(tab) : picked;
      requireCurrent(review);
      open(BookmarkEditSession.add(state, tab, captured));
    } catch (RuntimeException failure) {
      state.error("BOOKMARK_DIALOG_FAILED", EditorEvents.Subsystem.APPLICATION, message(failure));
    }
  }

  public void openTeleport(Tab tab) {
    if (pending()) {
      state.status = "Finish the open bookmark dialog first";
      return;
    }
    try {
      var review = context(tab);
      var captured = navigation.current(tab);
      requireCurrent(review);
      open(BookmarkEditSession.teleport(state, tab, captured));
    } catch (RuntimeException failure) {
      state.error("BOOKMARK_DIALOG_FAILED", EditorEvents.Subsystem.APPLICATION, message(failure));
    }
  }

  public void openRename(Tab tab, UUID id) {
    if (pending()) {
      state.status = "Finish the open bookmark dialog first";
      return;
    }
    try {
      open(BookmarkEditSession.rename(state, tab, id));
    } catch (RuntimeException failure) {
      state.error("BOOKMARK_DIALOG_FAILED", EditorEvents.Subsystem.APPLICATION, message(failure));
    }
  }

  private void open(BookmarkEditSession next) {
    var buffers = new LinkedHashMap<String, ImString>();
    for (var axis : next.axes())
      buffers.put(axis, new ImString(next.text(axis), 4 * CoordinateText.MAX_CHARACTERS + 1));
    var nameBuffer =
        new ImString(next.name(), Math.addExact(Math.multiplyExact(4, next.nameLimit()), 1));
    choiceOpenings++;
    choiceSessions.values().forEach(SelectorSession::retire);
    actionScope.release();
    session = next;
    revealBody = true;
    revealFeedback = false;
    revealField = next.named() ? "Name" : "X";
    name = nameBuffer;
    closing = false;
    coordinateBuffers.clear();
    coordinateBuffers.putAll(buffers);
    error = "";
    pending = true;
  }

  public void drawDialogs() {
    diagnosticRows = 0;
    if (session == null) {
      if (closing && layout.begin("Bookmark Coordinates")) {
        ImGui.closeCurrentPopup();
        ImGui.endPopup();
      }
      closing = false;
      return;
    }
    if (pending) {
      layout.open("Bookmark Coordinates");
      pending = false;
    }
    var owner = session;
    var document = state.document.sessionToken();
    long eligibility = owner.current() ? 1 : 0;
    long request = actionScope.synchronize(document, owner, eligibility);
    if (!layout.begin("Bookmark Coordinates")) return;
    fieldIds.clear();
    chrome.begin("bookmark-body");
    if (revealBody) {
      ImGui.setScrollY(0);
      revealBody = false;
    }
    ImGui.textWrapped(owner.title() + " / " + owner.tab().key());
    ImGui.pushID("bookmark-fields:" + request);
    if (owner.named()) {
      ImGui.textWrapped("Name");
      if (ImGui.isWindowAppearing() || "Name".equals(revealField)) ImGui.setKeyboardFocusHere();
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      fieldIds.put("Name", ImGui.getID("##name"));
      if (ImGui.inputText("##name", name, ImGuiInputTextFlags.AutoSelectAll)) {
        try {
          owner.editName(name.get());
          error = "";
        } catch (RuntimeException failure) {
          error = message(failure);
        }
      }
      record("Name");
      reveal("Name");
    }
    if (owner.axes().isEmpty()) ImGui.textWrapped(owner.renamedCoordinates().label());
    else {
      for (var axis : owner.axes()) {
        var buffer = coordinateBuffers.get(axis);
        ImGui.textWrapped(axis);
        if (axis.equals(revealField)
            || !owner.named() && ImGui.isWindowAppearing() && axis.equals("X"))
          ImGui.setKeyboardFocusHere();
        ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
        fieldIds.put(axis, ImGui.getID("##" + axis));
        if (ImGui.inputText("##" + axis, buffer)) {
          try {
            owner.edit(axis, buffer.get());
            error = "";
          } catch (RuntimeException failure) {
            error = message(failure);
          }
        }
        record(axis);
        reveal(axis);
      }
    }
    ImGui.popID();
    boolean current = owner.current();
    ImGui.separator();
    ImGui.textWrapped("Attempt feedback");
    if (!current)
      ImGui.textWrapped(
          "Document or bookmark changed. Your text is preserved; Cancel and reopen this dialog.");
    if (owner.result().issueCount() > 0) {
      for (var issue : owner.result().issues().stream().limit(8).toList()) {
        diagnosticRows++;
        ImGui.textWrapped(bounded(issue.toString()));
      }
      ImGui.textWrapped(
          owner.result().issueCount() + " candidate diagnostic(s); showing up to eight.");
    }
    if (!error.isEmpty()) ImGui.textWrapped("! " + bounded(error));
    else if (current && owner.result().issueCount() == 0)
      ImGui.textWrapped(
          "Apply validates all displayed fields together. Teleport changes only the view.");
    if (revealFeedback) {
      ImGui.setScrollHereY(1);
      revealFeedback = false;
    }
    chrome.end();
    ImGui.pushID("bookmark-actions:" + request);
    ImGui.beginDisabled(!current);
    boolean apply = chrome.identifiedAction("apply", "Apply");
    record("Apply");
    ImGui.endDisabled();
    boolean cancel = chrome.identifiedAction("cancel", "Cancel");
    record("Cancel");
    boolean fields = chrome.identifiedAction("fields", "Show Fields");
    record("Show Fields");
    boolean feedback = chrome.identifiedAction("feedback", "Show Feedback");
    record("Show Feedback");
    ImGui.popID();
    if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) close(owner);
    else if (session == owner
        && actionScope.current(state.document.sessionToken(), owner, owner.current() ? 1 : 0)) {
      if (fields) {
        revealBody = true;
        revealField = owner.named() ? "Name" : "X";
      } else if (feedback) revealFeedback = true;
      else if (apply && current) apply(owner);
    }
    ImGui.endPopup();
  }

  private void reveal(String field) {
    if (field.equals(revealField)) {
      ImGui.setScrollHereY(.5f);
      revealField = null;
    }
  }

  private String problemField(BookmarkEditSession owner) {
    if (owner.named() && !Objects.equals(name.get(), owner.name())) return "Name";
    for (var axis : owner.axes())
      if (!Objects.equals(coordinateBuffers.get(axis).get(), owner.text(axis))) return axis;
    return owner.problemField();
  }

  private void apply(BookmarkEditSession owner) {
    try {
      var displayed = new LinkedHashMap<String, String>();
      coordinateBuffers.forEach((axis, buffer) -> displayed.put(axis, buffer.get()));
      if (owner.apply(owner.named() ? name.get() : null, displayed, navigation::teleport)) {
        if (owner.uuid() != null) selected.put(owner.tab(), owner.uuid());
        close(owner);
      } else if (session == owner) {
        error =
            owner.result().failure().isEmpty()
                ? "Bookmark edit rejected. Correct the values or inspect Validation Results."
                : "Bookmark edit could not be applied. Your text is preserved; retry or inspect"
                    + " Logs.";
        revealFeedback = true;
      } else close(owner);
    } catch (RuntimeException failure) {
      if (session == owner) {
        error = message(failure);
        revealField = problemField(owner);
        revealFeedback = revealField == null;
      } else close(owner);
    }
  }

  /** A callback may cancel this opening and admit a successor before it returns. */
  private void close(BookmarkEditSession owner) {
    if (session == owner) cancel();
    ImGui.closeCurrentPopup();
    if (session == null) closing = false;
  }

  public void cancel() {
    choiceOpenings++;
    choiceSessions.values().forEach(SelectorSession::retire);
    closing = session != null || closing;
    if (session != null) session.cancel();
    session = null;
    actionScope.release();
    revealBody = revealFeedback = false;
    revealField = null;
    fieldIds.clear();
    pending = false;
    coordinateBuffers.clear();
    name = new ImString(4 * BookmarkEditSession.MAX_NAME_CHARACTERS + 1);
    error = "";
  }

  private static String message(RuntimeException failure) {
    var text = failure.getMessage();
    return text == null || text.isBlank() ? failure.toString() : text;
  }

  private static String bounded(String value) {
    return StatusBarText.clip(value, 1024);
  }

  private static float[] bounds() {
    var min = ImGui.getItemRectMin();
    var max = ImGui.getItemRectMax();
    return new float[] {(min.x + max.x) / 2, (min.y + max.y) / 2};
  }

  private void record(String control) {
    controls.put(control, bounds());
  }

  UUID selected(Tab tab) {
    return selected.get(tab);
  }

  float[] control(String id) {
    var bounds = controls.get(id);
    if (bounds == null)
      throw new AssertionError(
          "Missing bookmark control "
              + id
              + "; action="
              + (session == null ? "closed" : session.title())
              + "; pending="
              + pending
              + "; owner="
              + (session == null ? "none" : session.tab()));
    return bounds.clone();
  }

  float[] option(UUID uuid) {
    return options.get(uuid).clone();
  }

  int submitted() {
    return submitted;
  }

  int builds() {
    return cache.builds();
  }

  boolean pending() {
    return session != null && !session.closed();
  }

  String rawText(String field) {
    return field.equals("Name") ? name.get() : coordinateBuffers.get(field).get();
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
  }

  void probeEdit(String field, String value) {
    if ("Name".equals(field)) {
      session.editName(value);
      name.set(value);
    } else {
      session.edit(field, value);
      coordinateBuffers.get(field).set(value);
    }
  }

  UUID probeUuid() {
    return session == null ? null : session.uuid();
  }

  int candidateIssueCount() {
    return session == null ? 0 : session.result().issueCount();
  }

  int diagnosticRows() {
    return diagnosticRows;
  }

  Map<String, Integer> toolbarIds(Tab tab) {
    return Map.copyOf(toolbar(tab).ids);
  }

  Map<String, DialogBounds.Rect> toolbarBounds(Tab tab) {
    return Map.copyOf(toolbar(tab).bounds);
  }

  Map<String, Integer> choiceIds() {
    return Map.copyOf(choiceIds);
  }

  float choiceStride() {
    return choiceStride;
  }

  String choiceFeedback(Tab tab) {
    return choices(tab).feedback();
  }

  String query(Tab tab) {
    return filters.get(tab).get();
  }

  void probeQuery(Tab tab, String value) {
    filters.get(tab).set(value);
  }

  void probeOpenChoices(Tab tab) {
    choiceOpenings++;
    choices(tab).retire();
    probeOpen = tab;
  }

  void probeSelect(Tab tab, UUID id) {
    synchronize();
    if (cache.get(state.project(), state.revision, tab).get(id) == null)
      throw new IllegalArgumentException("Unknown bookmark");
    selected.put(tab, id);
  }
}
