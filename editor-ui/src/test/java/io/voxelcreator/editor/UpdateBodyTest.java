package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred bounded reactive body ownership and strict wire decoding; no HTTP requests. */
class UpdateBodyTest {
  private static final class Subscription implements Flow.Subscription {
    long requested;
    boolean cancelled;

    public void request(long count) {
      requested += count;
    }

    public void cancel() {
      cancelled = true;
    }
  }

  @Test
  void exactBoundAcrossPartitionsIsOwnedAndRequestsOneBatchAtATime() {
    var body = new UpdateBody();
    var subscription = new Subscription();
    body.onSubscribe(subscription);
    byte[] first = new byte[32767], last = new byte[32769];
    Arrays.fill(first, (byte) 1);
    Arrays.fill(last, (byte) 2);
    body.onNext(List.of(ByteBuffer.wrap(first)));
    body.onNext(List.of(ByteBuffer.wrap(last)));
    body.onComplete();
    var result = body.getBody().toCompletableFuture().join();
    first[0] = 8;
    last[0] = 9;
    assertEquals(65536, result.length);
    assertEquals(1, result[0]);
    assertEquals(2, result[32767]);
    assertEquals(3, subscription.requested);
    assertFalse(subscription.cancelled);
  }

  @Test
  void excessChunkCancelsBeforeConsumingItAndLateCallbacksCannotPublish() {
    var body = new UpdateBody();
    var subscription = new Subscription();
    body.onSubscribe(subscription);
    body.onNext(List.of(ByteBuffer.wrap(new byte[65536])));
    var excess = ByteBuffer.wrap(new byte[] {7});
    body.onNext(List.of(excess));
    assertTrue(subscription.cancelled);
    assertEquals(0, excess.position());
    var failure =
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    assertInstanceOf(IOException.class, failure.getCause());
    body.onComplete();
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {9})));
    assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
  }

  @Test
  void cancellationBeforeSubscriptionRejectsLaterSubscriptionAndBodyDelivery() {
    var body = new UpdateBody();
    body.cancel();
    var subscription = new Subscription();
    body.onSubscribe(subscription);
    assertTrue(subscription.cancelled);
    assertEquals(0, subscription.requested);
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
    body.onComplete();
    assertThrows(CancellationException.class, () -> body.getBody().toCompletableFuture().join());
  }

  @Test
  void duplicateSubscriptionsAreCancelledAndOriginalFailureIsRetained() {
    var body = new UpdateBody();
    var first = new Subscription();
    var duplicate = new Subscription();
    body.onSubscribe(first);
    body.onSubscribe(duplicate);
    assertTrue(duplicate.cancelled);
    assertFalse(first.cancelled);
    var failure = new IOException("fixture failure");
    body.onError(failure);
    body.onError(new IOException("later"));
    var caught =
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    assertSame(failure, caught.getCause());
    assertTrue(first.cancelled);
  }

  @Test
  void decodingRejectsMalformedUtf8AndOversizeRatherThanReplacingBytes() throws Exception {
    assertEquals("雪", UpdateChecker.decode("雪".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IOException.class, () -> UpdateChecker.decode(new byte[] {(byte) 0xc3, 0x28}));
    assertThrows(IOException.class, () -> UpdateChecker.decode(new byte[65537]));
  }

  @Test
  void nonSuccessHeadersRejectBeforeBodySubscriptionConsumesAnyBytes() {
    var body = new UpdateBody();
    var returned =
        UpdateChecker.handler(body)
            .apply(
                new java.net.http.HttpResponse.ResponseInfo() {
                  public int statusCode() {
                    return 302;
                  }

                  public java.net.http.HttpHeaders headers() {
                    return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
                  }

                  public java.net.http.HttpClient.Version version() {
                    return java.net.http.HttpClient.Version.HTTP_1_1;
                  }
                });
    assertSame(body, returned);
    var subscription = new Subscription();
    body.onSubscribe(subscription);
    assertTrue(subscription.cancelled);
    assertEquals(0, subscription.requested);
    var failure =
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    assertEquals("Metadata HTTP 302", failure.getCause().getMessage());
  }
}
