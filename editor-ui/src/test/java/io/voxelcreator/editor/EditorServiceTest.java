package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;

public class EditorServiceTest {
  @Test
  void duplicateRemapsInternalBindingsButPreservesExternalNoiseReference() {
    var n = new Noise();
    n.id = "original";
    var a = ProcessingRegistry.create("ADD", "a");
    n.processing.add(a);
    var b = ProcessingRegistry.create("MULTIPLY", "b");
    b.inputs.put("a", Binding.earlier(a.uuid));
    var external = UUID.randomUUID();
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = external;
    b.inputs.put("b", ref);
    n.processing.add(b);
    n.outputBinding = Binding.earlier(b.uuid);
    var copy = ResourceTransactions.duplicate(n, Noise.class);
    assertNotEquals(n.uuid, copy.uuid);
    assertNotEquals(a.uuid, copy.processing.getFirst().uuid);
    assertEquals(copy.processing.getFirst().uuid, copy.processing.get(1).inputs.get("a").itemUuid);
    assertEquals(copy.processing.get(1).uuid, copy.outputBinding.itemUuid);
    assertEquals(external, copy.processing.get(1).inputs.get("b").noiseGeneratorUuid);
  }

  @Test
  void importCopiesPreservePlacementClosureAndResolveNames() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "height";
    p.noise2D.add(n);
    var l = new Layer();
    l.id = "layer";
    var op = new Operation();
    op.id = "placement";
    op.noiseGeneratorUuid = n.uuid;
    l.operations.add(op);
    p.worldGenerator.layers.add(l);
    ResourceTransactions.mergeCopies(p, p);
    assertEquals(2, p.noise2D.size());
    assertEquals("height-copy", p.noise2D.get(1).id);
    assertEquals(
        p.noise2D.get(1).uuid,
        p.worldGenerator.layers.get(1).operations.getFirst().noiseGeneratorUuid);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
  }

  @Test
  void trustedMetadataRejectsUnsafeOriginsAndUnknownFields() {
    Set<String> trust = Set.of("https://releases.example.com");
    assertEquals(
        "https://releases.example.com",
        UpdateChecker.origin(URI.create("https://releases.example.com:443/metadata.json")));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateChecker.origin(URI.create("http://releases.example.com")));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateChecker.origin(URI.create("https://user:password@releases.example.com")));
    String valid =
        "{\"formatVersion\":1,\"version\":\"0.1.1\",\"releaseUrl\":\"https://releases.example.com/0.1.1\"}";
    assertEquals("0.1.1", UpdateChecker.parse(valid, trust).version());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UpdateChecker.parse(
                valid.replace("https://releases.example.com/0.1.1", "https://evil.example/0.1.1"),
                trust));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateChecker.parse(valid.replace("0.1.1", "not-a-version"), trust));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UpdateChecker.parse(valid.replace("1,\"version", "1,\"extra\":true,\"version"), trust));
  }

  @Test
  void shortcutsConflictWithoutTouchingDocument() {
    var u = new UserConfig();
    var document = new DocumentSession();
    u.input.shortcuts.put("Find", "Ctrl+S");
    assertThrows(IllegalArgumentException.class, u::validate);
    assertFalse(document.dirty());
  }
}
