package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred independent pointer geometry, release, cancellation and history contracts. */
class CurvePointerGestureTest {
  private record Fixture(
      CurveEditSession edit, CurveView view, CurvePointerGesture pointer, UUID id) {
    CurvePointerGesture.Plot plot() {
      return new CurvePointerGesture.Plot(10, 20, 200, 100, view.bounds());
    }

    void begin() {
      assertTrue(pointer.beginPoints(plot(), 110, 70, Set.of(id)));
    }

    boolean move(double x, double y) {
      return pointer.move(plot(), x, y, false, false, .1, .1);
    }

    Point point() {
      return CurveEditSession.point(edit.curve(), id);
    }
  }

  private Fixture fixture() {
    var c = new Curve();
    var middle = new Point(.5, .5);
    c.points.add(1, middle);
    var edit = new CurveEditSession(c);
    var view = new CurveView();
    view.synchronize(0, 1, 0, 1);
    return new Fixture(edit, view, new CurvePointerGesture(edit, view), middle.uuid);
  }

  @Test
  void finalReleaseCoordinatesUseTheOpeningGeometryAndMakeOneHistoryEntry() {
    var f = fixture();
    f.begin();
    assertTrue(f.move(120, 60));
    assertTrue(f.pointer.release(f.plot(), 130, 40, false, false, .1, .1));
    assertEquals(.6, f.point().x, 1e-14);
    assertEquals(.8, f.point().y, 1e-14);
    assertFalse(f.pointer.active());
    f.edit.undo();
    assertEquals(.5, f.point().x);
    assertEquals(.5, f.point().y);
    assertFalse(f.edit.canUndo());
    f.edit.redo();
    assertEquals(.8, f.point().y, 1e-14);
  }

  @Test
  void aClickOrSmallJitterDoesNotSnapAnUnmovedPoint() {
    var f = fixture();
    f.begin();
    assertTrue(f.pointer.release(f.plot(), 112, 71, true, true, .3, .3));
    assertEquals(.5, f.point().x);
    assertEquals(.5, f.point().y);
    assertFalse(f.edit.canUndo());
  }

  @Test
  void cancelledMovementRestoresTheExactBaselineAndKeepsExistingRedo() {
    var f = fixture();
    f.edit.edit(c -> c.displayMax = 2);
    f.edit.undo();
    var before = ProjectIO.JSON.valueToTree(f.edit.curve());
    f.begin();
    assertTrue(f.move(125, 50));
    f.pointer.cancel();
    assertEquals(before, ProjectIO.JSON.valueToTree(f.edit.curve()));
    assertFalse(f.pointer.active());
    assertFalse(f.edit.canUndo());
    assertTrue(f.edit.canRedo());
    assertFalse(f.move(140, 20));
    f.edit.redo();
    assertEquals(2, f.edit.curve().displayMax);
  }

  @Test
  void resizedOrMovedPlotsCancelInsteadOfReinterpretingTheHeldPointer() {
    for (int mode = 0; mode < 3; mode++) {
      var f = fixture();
      var before = ProjectIO.JSON.valueToTree(f.edit.curve());
      f.begin();
      f.move(120, 60);
      var changed =
          new CurvePointerGesture.Plot(
              mode == 0 ? 11 : 10,
              20,
              mode == 1 ? 400 : 200,
              mode == 2 ? 200 : 100,
              f.view.bounds());
      assertFalse(f.pointer.move(changed, 150, 40, false, false, .1, .1));
      assertEquals(before, ProjectIO.JSON.valueToTree(f.edit.curve()));
      assertFalse(f.edit.canUndo());
      assertFalse(f.pointer.active());
    }
  }

  @Test
  void ownedSelectionCannotBeRetargetedWhileHeldAndExternalEditsWin() {
    var f = fixture();
    var ids = new HashSet<>(Set.of(f.id));
    assertTrue(f.pointer.beginPoints(f.plot(), 110, 70, ids));
    ids.clear();
    ids.add(f.edit.curve().points.getFirst().uuid);
    assertTrue(f.move(120, 60));
    assertEquals(.55, f.point().x, 1e-14);
    assertEquals(0, f.edit.curve().points.getFirst().y);
    assertFalse(f.pointer.beginPan(f.plot(), 1, 2));
    assertTrue(f.edit.edit(c -> c.points.getLast().y = 3));
    var external = ProjectIO.JSON.valueToTree(f.edit.curve());
    assertFalse(f.move(130, 30));
    assertEquals(external, ProjectIO.JSON.valueToTree(f.edit.curve()));
    assertFalse(f.pointer.active());
  }

  @Test
  void aRejectedDiscreteEditEndsPointerOwnershipEvenWhenTheCurveObjectIsUnchanged() {
    var f = fixture();
    f.begin();
    assertTrue(f.move(120, 60));
    var accepted = ProjectIO.JSON.valueToTree(f.edit.curve());
    assertFalse(f.edit.edit(c -> c.max = c.min));
    assertFalse(f.move(140, 20));
    assertFalse(f.pointer.active());
    assertEquals(accepted, ProjectIO.JSON.valueToTree(f.edit.curve()));
    f.edit.undo();
    assertEquals(.5, f.point().x);
    assertEquals(.5, f.point().y);
    assertFalse(f.edit.canUndo());
  }

  @Test
  void newViewWinsWhileTheOwnedPointPreviewRollsBack() {
    var f = fixture();
    f.begin();
    f.move(120, 50);
    f.view.centerAt(3, 4);
    var external = f.view.bounds();
    assertFalse(f.move(140, 30));
    assertEquals(.5, f.point().x);
    assertEquals(.5, f.point().y);
    assertSame(external, f.view.bounds());
    assertFalse(f.edit.canUndo());
  }

  @Test
  void absolutePanIsIndependentOfEventPartitionsAndIncludesReleaseMovement() {
    var a = fixture();
    var b = fixture();
    assertTrue(a.pointer.beginPan(a.plot(), 110, 70));
    assertTrue(b.pointer.beginPan(b.plot(), 110, 70));
    for (int i = 1; i <= 15; i++) a.move(110 + i * 2, 70 + i);
    assertTrue(a.pointer.release(a.plot(), 150, 90, false, false, 1, 1));
    assertTrue(b.pointer.release(b.plot(), 150, 90, false, false, 1, 1));
    assertEquals(new CurveView.Bounds(-.2, .8, .2, 1.2), a.view.bounds());
    assertEquals(a.view.bounds(), b.view.bounds());
    assertFalse(a.edit.canUndo());
    assertFalse(a.pointer.active());
  }

  @Test
  void panCancellationRestoresItsBaselineButNeverOverwritesNewNavigation() {
    var f = fixture();
    var baseline = f.view.bounds();
    assertTrue(f.pointer.beginPan(f.plot(), 110, 70));
    f.move(150, 90);
    f.pointer.cancel();
    assertSame(baseline, f.view.bounds());
    assertTrue(f.pointer.beginPan(f.plot(), 110, 70));
    f.move(150, 90);
    assertTrue(f.view.centerAt(4, 6));
    var external = f.view.bounds();
    f.pointer.cancel();
    assertSame(external, f.view.bounds());
    assertFalse(f.edit.canUndo());
  }

  @Test
  void tangentReleaseUsesTheBaselineSlopeAndAutoCannotStartAnEdit() {
    var f = fixture();
    assertFalse(f.pointer.beginTangent(f.plot(), 110, 70, f.id, 1, .2));
    assertFalse(f.pointer.active());
    f.edit.edit(
        c -> {
          var point = CurveEditSession.point(c, f.id);
          point.handleMode = "ALIGNED";
          point.inTangent = point.outTangent = 1;
        });
    assertTrue(f.pointer.beginTangent(f.plot(), 110, 70, f.id, 1, .2));
    f.move(110, 60);
    f.pointer.release(f.plot(), 110, 50, false, false, 1, 1);
    assertEquals(2, f.point().outTangent, 1e-14);
    assertEquals(2, f.point().inTangent, 1e-14);
    f.edit.undo();
    assertEquals(1, f.point().outTangent);
  }

  @Test
  void invalidReleaseRetainsLastValidPreviewAndMissingCoordinatesCancel() {
    var f = fixture();
    f.begin();
    f.move(120, 60);
    assertFalse(f.pointer.release(f.plot(), 400, 40, false, false, 1, 1));
    assertEquals(.55, f.point().x, 1e-14);
    assertEquals(.6, f.point().y, 1e-14);
    assertTrue(f.edit.canUndo());
    f.edit.undo();
    f.begin();
    f.move(125, 40);
    assertFalse(f.move(Double.NaN, 0));
    assertEquals(.5, f.point().x);
    assertEquals(.5, f.point().y);
    assertFalse(f.edit.canUndo());
    assertTrue(f.edit.canRedo());
  }
}
