package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.DocumentSession;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewDocumentLifetimeTest {
  @Test
  void editsHistoryAndSaveRetainLifetimeButSameUuidReopenAndNewRetireIt(@TempDir Path out)
      throws Exception {
    var document = new DocumentSession();
    var lifetime = new PreviewDocumentLifetime(document.sessionToken());
    var count = new AtomicInteger();
    var id = document.data().project.uuid;
    document.edit(p -> p.project.seed = 42);
    document.undo();
    document.redo();
    var file = out.resolve("project.json");
    document.save(file);
    assertFalse(lifetime.synchronize(document.sessionToken(), count::incrementAndGet));
    assertTrue(document.open(file).isEmpty());
    assertEquals(id, document.data().project.uuid);
    assertTrue(lifetime.synchronize(document.sessionToken(), count::incrementAndGet));
    assertFalse(lifetime.synchronize(document.sessionToken(), count::incrementAndGet));
    document.newProject();
    assertTrue(lifetime.synchronize(document.sessionToken(), count::incrementAndGet));
    assertEquals(2, count.get());
  }

  @Test
  void failedRetirementCannotPublishTheNewLifetimeAndLateCpuResultsStayRetired() {
    var original = new Object();
    var replacement = new Object();
    var lifetime = new PreviewDocumentLifetime(original);
    var mailbox = new PreviewMailbox<String>();
    var oldToken = mailbox.token(0);
    assertTrue(mailbox.publish(0, "old accepted result"));
    assertThrows(
        IllegalStateException.class,
        () ->
            lifetime.synchronize(
                replacement,
                () -> {
                  mailbox.advance(1);
                  throw new IllegalStateException("Native retirement failed");
                }));
    assertTrue(oldToken.isCancelled());
    assertFalse(mailbox.publish(0, "late success"));
    assertFalse(mailbox.publish(0, "late failure"));
    assertNull(mailbox.take());
    assertTrue(lifetime.synchronize(replacement, () -> mailbox.advance(2)));
    assertFalse(lifetime.synchronize(replacement, () -> fail("Repeated retirement")));
    assertTrue(mailbox.publish(2, "replacement result"));
    assertEquals("replacement result", mailbox.take());
  }
}
