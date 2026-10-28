package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpressionFeedbackTest {
  @Test
  void parserSpansSelectCurrentUnicodeTokenAndEndOfInputWithoutBindingRoutes() {
    var project = new ProjectData();
    var noise = new Noise();
    var item = ProcessingRegistry.create("EXPRESSION", "formula");
    item.inputs.put("é", Binding.constant(2));
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("é+\nunknown"));
    var feedback = new ExpressionFeedback();
    var error = feedback.inspect(project, noise, item);
    assertFalse(error.valid());
    assertNull(error.input());
    assertEquals(3, error.start());
    assertEquals(10, error.end());
    assertEquals(
        new ExpressionSelection.Range(4, 11),
        ExpressionSelection.utf8Range("é+\nunknown", error.start(), error.end()));
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
    error = feedback.inspect(project, noise, item);
    assertFalse(error.valid());
    assertEquals(4, error.start());
    assertEquals(4, error.end());
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("é+2"));
    assertTrue(feedback.inspect(project, noise, item).valid());
  }

  @Test
  void invalidNamedBindingsAreDistinguishedFromParserSpansAndNeverCached() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "n";
    var item = ProcessingRegistry.create("EXPRESSION", "formula");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+unknown"));
    var binding = Binding.constant(0);
    binding.value = null;
    item.inputs.put("bias", binding);
    var feedback = new ExpressionFeedback();
    var bad = feedback.inspect(p, n, item);
    assertFalse(bad.valid());
    assertEquals("bias", bad.input());
    assertEquals(0, feedback.cachedEntries());
    binding.value = ProjectIO.JSON.valueToTree(2);
    var parser = feedback.inspect(p, n, item);
    assertNull(parser.input());
    assertEquals(2, parser.start());
    assertEquals(9, parser.end());
    assertSame(parser, feedback.inspect(p, n, item));
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = java.util.UUID.randomUUID();
    assertEquals("bias", feedback.inspect(p, n, item).input());
    binding.sourceKind = "Constant";
    binding.type = "Vector3";
    assertEquals("bias", feedback.inspect(p, n, item).input());
    item.inputs.put("bias", null);
    assertEquals("bias", feedback.inspect(p, n, item).input());
  }

  @Test
  void historicalFeedbackConstructorHasNoBindingDestination() {
    var old = new ExpressionFeedback.Result("bad", 1, 2, null, java.util.List.of(), null);
    assertNull(old.input());
    assertFalse(old.valid());
  }

  @Test
  void parserFeedbackTracksTypesSharedValuesAndErrorSpans() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "noise";
    p.noise2D.add(n);
    var parameter = new Parameter();
    parameter.id = "bias";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    n.sharedParameters.add(parameter);
    var item = ProcessingRegistry.create("EXPRESSION", "expression");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("true ? bias*2 : x"));
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = parameter.uuid;
    item.inputs.put("bias", binding);
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    var cache = new ExpressionFeedback();
    var a = cache.inspect(p, n, item);
    assertTrue(a.valid());
    assertTrue(a.inputs().isEmpty());
    assertTrue(a.statistics().prunedBranches() > 0);
    assertSame(a, cache.inspect(p, n, item));
    parameter.value = ProjectIO.JSON.valueToTree(3);
    assertNotSame(a, cache.inspect(p, n, item));
    assertEquals(2, cache.cachedEntries());
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+unknown"));
    var error = cache.inspect(p, n, item);
    assertFalse(error.valid());
    assertEquals(2, error.start());
    assertEquals(9, error.end());
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x>0"));
    assertFalse(cache.inspect(p, n, item).valid());
    item.resultType = "Boolean";
    assertTrue(cache.inspect(p, n, item).valid());
    binding.type = "Scalar";
    binding.sourceKind = "Constant";
    binding.value = null;
    assertFalse(cache.inspect(p, n, item).valid());
    binding.type = null;
    assertFalse(cache.inspect(p, n, item).valid());
  }

  @Test
  void feedbackCacheIsBounded() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "noise";
    var item = ProcessingRegistry.create("EXPRESSION", "expression");
    var cache = new ExpressionFeedback();
    ExpressionFeedback.Result last = null;
    for (int i = 0; i < 200; i++) {
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+" + i));
      last = cache.inspect(p, n, item);
      assertTrue(last.valid());
    }
    assertEquals(128, cache.cachedEntries());
    assertSame(last, cache.inspect(p, n, item));
  }

  @Test
  void displayFeedbackNeverDirtiesTheDocument(@TempDir Path directory) throws Exception {
    var session = new DocumentSession();
    assertTrue(
        session
            .edit(
                p -> {
                  var n = new Noise();
                  n.id = "noise";
                  var item = ProcessingRegistry.create("EXPRESSION", "expression");
                  item.parameters.put("expression", ProjectIO.JSON.valueToTree("x*x+x*x"));
                  n.processing.add(item);
                  n.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(n);
                })
            .isEmpty());
    session.save(directory.resolve("project.json"));
    var p = session.data();
    var before = ProjectIO.JSON.valueToTree(p);
    var n = p.noise2D.getFirst();
    assertTrue(new ExpressionFeedback().inspect(p, n, n.processing.getFirst()).valid());
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
    assertFalse(session.dirty());
  }
}
