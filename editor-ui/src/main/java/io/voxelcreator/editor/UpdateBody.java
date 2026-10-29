package io.voxelcreator.editor;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Back-pressured metadata subscriber accepting at most the fixed raw response byte bound. */
final class UpdateBody implements HttpResponse.BodySubscriber<byte[]> {
  static final int MAX_BYTES = 65536;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private Flow.Subscription subscription;

  public CompletionStage<byte[]> getBody() {
    return body;
  }

  public synchronized void onSubscribe(Flow.Subscription next) {
    Objects.requireNonNull(next);
    if (subscription != null || body.isDone()) {
      next.cancel();
      return;
    }
    subscription = next;
    next.request(1);
  }

  public synchronized void onNext(List<ByteBuffer> buffers) {
    if (body.isDone()) return;
    try {
      for (var buffer : buffers) {
        int count = buffer.remaining();
        if (count > MAX_BYTES - bytes.size()) {
          fail(new IOException("Metadata exceeds 64 KiB"));
          return;
        }
        byte[] chunk = new byte[count];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      if (subscription != null) subscription.request(1);
    } catch (RuntimeException invalid) {
      fail(invalid);
    }
  }

  public synchronized void onError(Throwable error) {
    fail(error);
  }

  public synchronized void onComplete() {
    if (body.complete(bytes.toByteArray())) bytes.reset();
  }

  synchronized void cancel() {
    fail(new CancellationException("Update request cancelled"));
  }

  private void fail(Throwable error) {
    if (body.completeExceptionally(error)) {
      bytes.reset();
      if (subscription != null) subscription.cancel();
    }
  }
}
