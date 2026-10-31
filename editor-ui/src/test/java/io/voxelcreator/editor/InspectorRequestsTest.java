package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InspectorRequestsTest {
  @Test
  void boundIdentityCannotBeBypassedByEqualValuesOrTheLegacyOverload() {
    var gate = new InspectorRequests();
    var document = new String("same UUID and revision");
    var request = gate.begin(document, 4, false);
    assertTrue(gate.current(request, document, 4));
    assertFalse(gate.current(request, new String(document), 4));
    assertFalse(gate.current(request, 4));
    gate.viewChanged();
    assertTrue(gate.current(request, document, 4));
  }

  @Test
  void sameUuidReopenRejectsCoordinateAndPickBeforeViewportSynchronization(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path out) throws Exception {
    var document = new io.voxelcreator.model.DocumentSession();
    var file = out.resolve("same-project.json");
    document.save(file);
    var id = document.data().project.uuid;
    for (boolean viewBound : new boolean[] {false, true}) {
      var gate = new InspectorRequests();
      var request = gate.begin(document.sessionToken(), 7, viewBound);
      assertTrue(document.open(file).isEmpty());
      assertEquals(id, document.data().project.uuid);
      assertFalse(gate.current(request, document.sessionToken(), 7));
      assertTrue(
          gate.current(
              gate.begin(document.sessionToken(), 7, viewBound), document.sessionToken(), 7));
    }
  }

  @Test
  void explicitCancellationRejectsCurrentWorkAndAllowsAnotherRequest() {
    for (boolean viewBound : new boolean[] {false, true}) {
      var gate = new InspectorRequests();
      var request = gate.begin(4, viewBound);
      gate.cancelCurrent();
      assertFalse(gate.current(request, 4));
      assertTrue(gate.current(gate.begin(4, viewBound), 4));
    }
  }

  @Test
  void aNewerIntentSupersedesBothCoordinateAndViewportWork() {
    var gate = new InspectorRequests();
    var pick = gate.begin(4, true);
    var coordinate = gate.begin(4, false);
    assertFalse(gate.current(pick, 4));
    assertTrue(gate.current(coordinate, 4));
    var nextPick = gate.begin(4, true);
    assertFalse(gate.current(coordinate, 4));
    assertTrue(gate.current(nextPick, 4));
  }

  @Test
  void viewChangesInvalidatePicksEvenWhenTheViewLaterReturns() {
    var gate = new InspectorRequests();
    var request = gate.begin(8, true);
    gate.viewChanged();
    gate.viewChanged();
    assertFalse(gate.current(request, 8));
    assertTrue(gate.current(gate.begin(8, true), 8));
  }

  @Test
  void explicitCoordinateTracingSurvivesCameraOrTabChanges() {
    var gate = new InspectorRequests();
    var coordinate = gate.begin(8, false);
    gate.viewChanged();
    assertTrue(gate.current(coordinate, 8));
  }

  @Test
  void documentChangesRejectBothRequestKinds() {
    for (boolean viewBound : new boolean[] {false, true}) {
      var gate = new InspectorRequests();
      var request = gate.begin(1, viewBound);
      assertFalse(gate.current(request, 2));
    }
  }

  @Test
  void closureRejectsQueuedPublicationAndNewWork() {
    for (boolean viewBound : new boolean[] {false, true}) {
      var gate = new InspectorRequests();
      var request = gate.begin(1, viewBound);
      gate.close();
      gate.close();
      gate.viewChanged();
      assertFalse(gate.current(request, 1));
      assertThrows(IllegalStateException.class, () -> gate.begin(1, viewBound));
    }
  }
}
