package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import org.junit.jupiter.api.Test;

class ResourceSearchSessionTest {
  @Test
  void repeatedDrawingReusesIndexAndResultsUntilTheirActualInputsChange() {
    var p = ResourceSearchIndexTest.fixture();
    var session = new ResourceSearchSession();
    var filter = ResourceSearchIndex.Filter.all();
    var first = session.search(p, 0, filter);
    for (int i = 0; i < 100; i++) assertSame(first, session.search(p, 0, filter));
    assertEquals(1, session.builds());
    assertEquals(1, session.queries());
    session.search(
        p,
        0,
        ResourceSearchIndexTest.filter(
            "consumer", "noise2D", "All", null, null, false, ResourceSearchIndex.Mode.ID));
    assertEquals(1, session.builds());
    assertEquals(2, session.queries());
    session.search(p, 1, filter);
    assertEquals(2, session.builds());
  }

  @Test
  void projectReplacementAndClearReleasePreviousResultsEvenAtTheSameRevision() {
    var p = ResourceSearchIndexTest.fixture();
    var session = new ResourceSearchSession();
    var filter = ResourceSearchIndex.Filter.all();
    session.search(p, 3, filter);
    assertTrue(session.search(new ProjectData(), 3, filter).isEmpty());
    assertEquals(2, session.builds());
    session.clear();
    assertEquals(0, session.indexed());
    session.search(p, 3, filter);
    assertEquals(3, session.builds());
  }
}
