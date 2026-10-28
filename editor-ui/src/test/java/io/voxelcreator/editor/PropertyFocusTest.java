package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PropertyFocusTest {
  private final UUID project = UUID.randomUUID(),
      resource = UUID.randomUUID(),
      control = UUID.randomUUID();
  private final Object draft = new Object();
  private final PropertyFocus focus = new PropertyFocus();

  private void request(String parameter) {
    focus.request(new EditorEvents.Target(project, resource, control, parameter, "", false), draft);
  }

  @Test
  void activationIsSubmittedOnceAndAcknowledgedWithoutRepeatedFocus() {
    request("parameters/radius");
    assertTrue(focus.matches(control, "parameters/radius"));
    assertTrue(focus.submit());
    assertFalse(focus.submit());
    focus.acknowledge("radius-widget");
    assertEquals("radius-widget", focus.acknowledged());
    assertFalse(focus.matches(control, "parameters/radius"));
    assertFalse(focus.submit());
  }

  @Test
  void matchingUsesExactControlAndDecodedLogicalKey() {
    request("parameters/a/b~c");
    assertFalse(focus.matches(UUID.randomUUID(), "parameters/a/b~c"));
    assertFalse(focus.matches(control, "parameters/a"));
    assertTrue(focus.matches(control, "parameters/a/b~c"));
    assertFalse(focus.matches(control, null));
  }

  @Test
  void replacementDraftWithTheSameIdsCancelsOutstandingWork() {
    request("parameters/value");
    focus.synchronize(project, resource, new Object());
    assertFalse(focus.matches(control, "parameters/value"));
    assertFalse(focus.submit());
  }

  @Test
  void changingProjectOrResourceCancelsOutstandingWork() {
    request("parameters/value");
    focus.synchronize(UUID.randomUUID(), resource, draft);
    assertFalse(focus.submit());
    request("parameters/value");
    focus.synchronize(project, UUID.randomUUID(), draft);
    assertFalse(focus.submit());
  }

  @Test
  void anotherRequestRearmsAndReplacesThePreviousAcknowledgement() {
    request("parameters/radius");
    focus.submit();
    focus.acknowledge("old");
    request("parameters/spacing");
    assertNull(focus.acknowledged());
    assertFalse(focus.matches(control, "parameters/radius"));
    assertTrue(focus.matches(control, "parameters/spacing"));
    assertTrue(focus.submit());
  }

  @Test
  void matchingDraftAndControlDoNotCancelAnUnacknowledgedRequest() {
    request("parameters/value");
    focus.synchronize(project, resource, draft);
    focus.chooseControl(control);
    assertTrue(focus.submit());
    focus.acknowledge("value");
    assertEquals("value", focus.acknowledged());
  }

  @Test
  void explicitManualSelectionAndClearCancelWithoutChangingAnyDocument() {
    request("parameters/value");
    focus.chooseControl(UUID.randomUUID());
    assertFalse(focus.submit());
    request("parameters/value");
    focus.cancel();
    focus.acknowledge("stale");
    assertNull(focus.acknowledged());
    assertFalse(focus.submit());
  }

  @Test
  void vectorWithoutALeafSelectsOnlyItsFirstComponent() {
    request("parameters/value");
    assertTrue(focus.matches(control, "parameters/value/0"));
    assertFalse(focus.matches(control, "parameters/value/1"));
    request("parameters/value/2");
    assertFalse(focus.matches(control, "parameters/value/0"));
    assertTrue(focus.matches(control, "parameters/value/2"));
  }

  @Test
  void acknowledgementRequiresAnActualSubmission() {
    request("parameters/value");
    focus.acknowledge("unsubmitted");
    assertNull(focus.acknowledged());
    assertTrue(focus.submit());
  }
}
