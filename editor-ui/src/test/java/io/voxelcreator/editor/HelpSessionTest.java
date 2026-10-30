package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HelpSessionTest {
  @Test
  void filteringAndUnknownLinksRetainTheActualSelectedTopicAndHistory() {
    var session = new HelpSession();
    session.synchronize(AppMetadata.defaults());
    assertTrue(session.open("noise"));
    var selected = session.selected();
    assertTrue(session.catalog().search("no-such-topic-123456").isEmpty());
    assertSame(selected, session.selected());
    assertFalse(session.open("unknown"));
    assertFalse(session.canBack());
    assertSame(selected, session.selected());
    assertTrue(session.open("layers"));
    assertTrue(session.back());
    assertSame(selected, session.selected());
    assertFalse(session.back());
  }

  @Test
  void publishedMetadataRefreshesTextWhileSamePublicationRetainsTheCachedCatalog() {
    var active = AppMetadata.defaults();
    var session = new HelpSession();
    session.synchronize(active);
    session.open("noise");
    var first = session.catalog();
    var draft = AppMetadata.copy(active);
    draft.controls.get("noise").label = "Private changed label";
    session.synchronize(active);
    assertSame(first, session.catalog());
    assertEquals("Noise Editor", session.selected().name());
    session.synchronize(draft);
    assertNotSame(first, session.catalog());
    assertEquals("noise", session.selected().id());
    assertEquals("Private changed label", session.selected().name());
    assertEquals("Noise Editor", first.topic("noise").name());
  }

  @Test
  void repeatedCurrentLinksDoNotGrowHistoryAndOlderThanThirtyTwoRoutesAreEvicted() {
    var session = new HelpSession();
    session.synchronize(AppMetadata.defaults());
    var topics = session.catalog().topics();
    assertTrue(topics.size() > 40);
    for (int i = 0; i < 40; i++) {
      session.open(topics.get(i).id());
      session.open(topics.get(i).id());
    }
    for (int i = 38; i >= 7; i--) {
      assertTrue(session.back());
      assertEquals(topics.get(i).id(), session.selected().id());
    }
    assertFalse(session.back());
  }

  @Test
  void helpRequestsAndNavigationLeaveAuthoringPreferencesAndInstrumentationAlone() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(state.project());
      var user = io.voxelcreator.model.ProjectIO.JSON.valueToTree(state.user);
      var metadata = state.metadata;
      var draft = state.draft;
      var engine = state.engine;
      long revision = state.revision, history = state.document.historyBytes();
      state.helpRequests.request("noise");
      state.helpRequests.request("help.find");
      var session = new HelpSession();
      session.synchronize(state.metadata);
      assertTrue(session.open(state.helpRequests.take().orElseThrow()));
      assertTrue(state.helpRequests.take().isEmpty());
      assertEquals("help.find", session.selected().id());
      assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, io.voxelcreator.model.ProjectIO.JSON.valueToTree(state.user));
      assertSame(metadata, state.metadata);
      assertSame(draft, state.draft);
      assertSame(engine, state.engine);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
      assertTrue(state.engine.profiler().current().isEmpty());
    }
  }
}
