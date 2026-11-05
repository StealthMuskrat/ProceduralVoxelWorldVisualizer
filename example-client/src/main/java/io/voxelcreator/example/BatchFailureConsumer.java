package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred engine-JAR-only replay of batch failure metadata and independent prefix arithmetic. */
public final class BatchFailureConsumer {
  private record Retained(double[] actual, double[] expected) {}

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var failed = new ArrayList<Retained>(); var healthy = new ArrayList<Retained>();
    long cases = 0, calls = 0, reads = 0, successful = 0;
    for (String route : List.of("GRID2", "GRID3", "POINTS2", "POINTS3")) {
      boolean threeDimensional = route.endsWith("3");
      double[] xs = {-3, -1, -3, -1}, ys = threeDimensional ? new double[] {5, 5, 2, 2} : new double[] {0, 0, 0, 0};
      double[] zs = threeDimensional ? new double[] {-7, -7, -7, -7} : new double[] {2, 2, -2, -2};
      double[] values = threeDimensional ? new double[] {-26, -22, -35, -31} : new double[] {5, 7, -11, -9};
      int[] addresses = route.equals("GRID2") ? new int[] {2, 3, 7, 8} : route.equals("GRID3") ? new int[] {1, 2, 7, 8} : new int[] {3, 4, 5, 6};
      for (int failedAt : new int[] {0, 3}) for (String mode : List.of("MUTABLE", "UNREADABLE_RUNTIME", "UNREADABLE_ERROR", "LONG")) {
        var invoked = new AtomicInteger(); var described = new AtomicInteger(); var destination = buffer();
        var cause = new RuntimeException() {
          public String getMessage() {
            int read = described.incrementAndGet();
            if (mode.equals("UNREADABLE_RUNTIME")) throw new IllegalStateException("message reader");
            if (mode.equals("UNREADABLE_ERROR")) throw new AssertionError("message reader");
            return mode.equals("LONG") ? "😀".repeat(2000) : "message-" + read;
          }
          public String toString() { throw new AssertionError("Host toString called"); }
        };
        NoiseSampler2D two = (x, z) -> {
          int at = invoked.getAndIncrement(); bits(xs[at], x); bits(zs[at], z);
          if (at == failedAt) throw cause; return x + 4 * z;
        };
        NoiseSampler3D three = (x, y, z) -> {
          int at = invoked.getAndIncrement(); bits(xs[at], x); bits(ys[at], y); bits(zs[at], z);
          if (at == failedAt) throw cause; return 2 * x + 3 * y + 5 * z;
        };
        try {
          invoke(route, two, three, destination); throw new AssertionError("Batch failure disappeared");
        } catch (BatchSamplingException failure) {
          require(failure.getCause() == cause && failure.sampleIndex == failedAt, "Failure lost its original cause/index");
          bits(xs[failedAt], failure.x); bits(ys[failedAt], failure.y); bits(zs[failedAt], failure.z);
          String detail = mode.startsWith("UNREADABLE") ? cause.getClass().getName() : mode.equals("LONG") ? "😀".repeat(1023) + "…" : "message-1";
          String expected = "Sample " + failedAt + " at " + xs[failedAt] + "," + ys[failedAt] + "," + zs[failedAt] + ": " + detail;
          require(expected.equals(failure.getMessage()) && expected.equals(failure.getMessage()), "Batch detail was not safely frozen");
          require(described.get() == 1 && invoked.get() == failedAt + 1 && cause.getSuppressed().length == 0, "Description retried sampling or changed host failure ownership");
        }
        var expected = buffer(); for (int i = 0; i < failedAt; i++) expected[addresses[i]] = values[i];
        failed.add(new Retained(destination, expected)); successful += failedAt; calls += invoked.get(); reads += described.get(); cases++;
      }
      var destination = buffer();
      invoke(route, (x, z) -> x + 4 * z, (x, y, z) -> 2 * x + 3 * y + 5 * z, destination);
      var expected = buffer(); for (int i = 0; i < 4; i++) expected[addresses[i]] = values[i];
      healthy.add(new Retained(destination, expected));
    }
    long failedCells = verify(failed), healthyCells = verify(healthy);
    require(cases == 32 && calls == 80 && reads == 32 && successful == 48 && failedCells == 384 && healthyCells == 48,
        "Unexpected independent batch-failure replay dimensions");
    Files.writeString(out.resolve("batch-failure-report.txt"),
        "cases=" + cases + "\nsamplerCalls=" + calls + "\nmessageReads=" + reads + "\nsuccessfulPrefixSamples=" + successful
            + "\nretainedFailedDestinationCells=" + failedCells + "\nuntouchedCells=" + (failedCells - successful)
            + "\nhealthySamples=16\nretainedHealthyDestinationCells=" + healthyCells + "\n");
    System.out.println("BATCH_FAILURE_CONSUMER_PASS");
  }

  private static double[] buffer() { var values = new double[12]; Arrays.fill(values, 777); return values; }
  private static void invoke(String route, NoiseSampler2D two, NoiseSampler3D three, double[] destination) {
    var token = new CancellationToken();
    switch (route) {
      case "GRID2" -> two.sampleGrid(-3, 2, 2, 2, 2, -4, destination, 2, 5, token);
      case "GRID3" -> three.sampleGrid(-3, 5, -7, 2, 2, 1, 2, -3, 0, destination, 1, 4, 6, token);
      case "POINTS2" -> two.samplePoints(new double[] {99, -3, -1, -3, -1, 99}, new double[] {99, 2, 2, -2, -2, 99}, 1, 4, destination, 3, token);
      case "POINTS3" -> three.samplePoints(new double[] {99, -3, -1, -3, -1, 99}, new double[] {99, 5, 5, 2, 2, 99},
          new double[] {99, -7, -7, -7, -7, 99}, 1, 4, destination, 3, token);
      default -> throw new AssertionError("Unknown batch route");
    }
  }
  private static long verify(List<Retained> results) {
    long cells = 0; for (var result : results) for (int i = 0; i < result.expected.length; i++) { bits(result.expected[i], result.actual[i]); cells++; }
    return cells;
  }
  private static void bits(double expected, double actual) { require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Independent batch value/coordinate changed"); }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
