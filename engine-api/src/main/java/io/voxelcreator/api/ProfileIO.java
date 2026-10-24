package io.voxelcreator.api;

import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit atomic exports; capture files never modify or become part of a project document. */
public final class ProfileIO {
  private ProfileIO() {}

  public static void writeJSON(Path path, Profiler.Snapshot snapshot) throws IOException {
    Objects.requireNonNull(snapshot);
    write(
        path,
        writer -> {
          var data = ProjectIO.JSON.createObjectNode();
          data.put("formatVersion", snapshot.formatVersion());
          data.put("captureId", snapshot.captureId().toString());
          data.put("startedAt", snapshot.startedAt().toString());
          data.set("options", ProjectIO.JSON.valueToTree(snapshot.options()));
          data.put("state", snapshot.state().name());
          data.put("elapsedNanos", snapshot.elapsedNanos());
          data.set("samples", ProjectIO.JSON.valueToTree(snapshot.samples()));
          data.set("series", ProjectIO.JSON.valueToTree(snapshot.series()));
          ProjectIO.JSON.writeValue(writer, data);
        });
  }

  public static void writeCSV(Path path, Profiler.Snapshot snapshot) throws IOException {
    Objects.requireNonNull(snapshot);
    write(
        path,
        writer -> {
          writer.write(
              "capture_id,state,started_at,context_id,project_uuid,kind,source_uuid,label,offset_ns,value,unit,outcome,chunk_x,chunk_y,chunk_z,thread_id,thread_name\r\n");
          for (var sample : snapshot.samples()) {
            var chunk = sample.chunk();
            Object[] fields = {
              snapshot.captureId(),
              snapshot.state(),
              snapshot.startedAt(),
              sample.contextId(),
              sample.projectUuid(),
              sample.kind(),
              sample.sourceUuid(),
              sample.label(),
              sample.offsetNanos(),
              sample.value(),
              sample.unit(),
              sample.outcome(),
              chunk == null ? null : chunk.x(),
              chunk == null ? null : chunk.y(),
              chunk == null ? null : chunk.z(),
              sample.threadId(),
              sample.threadName()
            };
            for (int i = 0; i < fields.length; i++) {
              if (i > 0) writer.write(',');
              writer.write(csv(fields[i]));
            }
            writer.write("\r\n");
          }
        });
  }

  private static String csv(Object value) {
    String text = value == null ? "" : value.toString();
    return text.indexOf(',') >= 0
            || text.indexOf('"') >= 0
            || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0
        ? '"' + text.replace("\"", "\"\"") + '"'
        : text;
  }

  @FunctionalInterface
  private interface Output {
    void write(Writer writer) throws IOException;
  }

  private static void write(Path path, Output output) throws IOException {
    Path destination = path.toAbsolutePath();
    Files.createDirectories(destination.getParent());
    Path temp = Files.createTempFile(destination.getParent(), ".profile-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        output.write(writer);
      }
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(
            temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
