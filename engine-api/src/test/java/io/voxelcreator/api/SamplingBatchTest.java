package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.EvaluationFailure;
import io.voxelcreator.core.Expression;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class SamplingBatchTest {
  @Test
  void stridedTwoDimensionalGridUsesDeclaredCoordinatesAndPreservesPadding() {
    NoiseSampler2D sampler = (x, z) -> 2 * x + 3 * z;
    double[] result = new double[30];
    Arrays.fill(result, -99);
    var written = new HashSet<Integer>();
    sampler.sampleGrid(-2, 3, 3, 3, .25, -.5, result, 2, 7, new CancellationToken());
    for (int z = 0; z < 3; z++)
      for (int x = 0; x < 3; x++) {
        int at = 2 + z * 7 + x;
        written.add(at);
        assertEquals(2 * (-2 + x * .25) + 3 * (3 - z * .5), result[at]);
      }
    for (int i = 0; i < result.length; i++) if (!written.contains(i)) assertEquals(-99, result[i]);
    double[] legacy = new double[6];
    sampler.sampleGrid(-1, 2, 3, 2, .5, legacy);
    assertArrayEquals(new double[] {4, 5, 6, 5.5, 6.5, 7.5}, legacy);
  }

  @Test
  void threeDimensionalLayoutRetainsXFastZRowsAndYPlanes() {
    NoiseSampler3D sampler = (x, y, z) -> x + 10 * y + 100 * z;
    double[] result = new double[30];
    Arrays.fill(result, -99);
    var written = new HashSet<Integer>();
    sampler.sampleGrid(-2, 3, 4, 3, 2, 2, .5, -2, .25, result, 1, 5, 13, new CancellationToken());
    for (int y = 0; y < 2; y++)
      for (int z = 0; z < 2; z++)
        for (int x = 0; x < 3; x++) {
          int at = 1 + y * 13 + z * 5 + x;
          written.add(at);
          assertEquals((-2 + x * .5) + 10 * (3 - y * 2) + 100 * (4 + z * .25), result[at]);
        }
    for (int i = 0; i < result.length; i++) if (!written.contains(i)) assertEquals(-99, result[i]);
    var legacy = new double[12];
    sampler.sampleGrid(0, 0, 0, 2, 2, 3, 1, legacy);
    assertEquals(100, legacy[2]);
    assertEquals(10, legacy[6]);
    assertEquals(211, legacy[11]);
  }

  @Test
  void oversizedAddressesCountsAndCoordinatesFailBeforeWriting() {
    var calls = new AtomicInteger();
    NoiseSampler3D sampler =
        (x, y, z) -> {
          calls.incrementAndGet();
          return 0;
        };
    double[] destination = {-9, -9, -9};
    assertThrows(
        IllegalArgumentException.class,
        () ->
            sampler.sampleGrid(
                0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, destination));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            sampler.sampleGrid(
                0,
                0,
                0,
                1,
                2,
                2,
                1,
                1,
                1,
                destination,
                0,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                new CancellationToken()));
    assertThrows(
        IllegalArgumentException.class,
        () -> sampler.sampleGrid(Double.MAX_VALUE, 0, 0, 2, 1, 1, Double.MAX_VALUE, destination));
    assertThrows(
        IllegalArgumentException.class,
        () -> sampler.sampleGrid(0, 0, 0, -1, 1, 1, 1, destination));
    assertEquals(0, calls.get());
    assertArrayEquals(new double[] {-9, -9, -9}, destination);
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () ->
            sampler.sampleGrid(
                0,
                0,
                0,
                0,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                1,
                1,
                1,
                destination,
                0,
                0,
                0,
                new CancellationToken(() -> Thread.currentThread().isInterrupted())));
  }

  @Test
  void pointPreflightRejectsNonfiniteCoordinatesAndOverlapsButAllowsDisjointRanges() {
    var calls = new AtomicInteger();
    NoiseSampler2D sampler =
        (x, z) -> {
          calls.incrementAndGet();
          return x + z;
        };
    var xs = new double[] {-1, 0, 2, 99, 99, 99};
    var zs = new double[] {4, 5, 6};
    assertThrows(
        IllegalArgumentException.class,
        () -> sampler.samplePoints(xs, zs, 0, 3, xs, 1, new CancellationToken()));
    assertEquals(0, calls.get());
    sampler.samplePoints(xs, zs, 0, 3, xs, 3, new CancellationToken());
    assertArrayEquals(new double[] {-1, 0, 2, 3, 5, 8}, xs);
    double[] output = {-9, -9, -9};
    zs[2] = Double.NaN;
    calls.set(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> sampler.samplePoints(xs, zs, 0, 3, output, 0, new CancellationToken()));
    assertEquals(0, calls.get());
    assertArrayEquals(new double[] {-9, -9, -9}, output);
  }

  @Test
  void failuresRetainCoordinatesCauseAndOnlyTheSuccessfulPrefix() {
    var cause = new ArithmeticException("bad sample");
    NoiseSampler2D sampler =
        (x, z) -> {
          if (x == 2) throw cause;
          return x + z;
        };
    double[] result = new double[8];
    Arrays.fill(result, -9);
    var failure =
        assertThrows(BatchSamplingException.class, () -> sampler.sampleGrid(0, 0, 4, 2, 1, result));
    assertEquals(2, failure.sampleIndex);
    assertEquals(2, failure.x);
    assertEquals(0, failure.y);
    assertEquals(0, failure.z);
    assertSame(cause, failure.getCause());
    assertArrayEquals(new double[] {0, 1, -9, -9, -9, -9, -9, -9}, result);
    NoiseSampler3D nonfinite = (x, y, z) -> x == 1 ? Double.NaN : x + y + z;
    var second =
        assertThrows(
            BatchSamplingException.class,
            () ->
                nonfinite.samplePoints(
                    new double[] {0, 1},
                    new double[] {2, 3},
                    new double[] {4, 5},
                    0,
                    2,
                    result,
                    2,
                    new CancellationToken()));
    assertEquals(1, second.sampleIndex);
    assertEquals(3, second.y);
    assertEquals(6, result[2]);
    assertEquals(-9, result[3]);
  }

  @Test
  void cancellationIsBoundedAndPreflightCancellationWritesNothing() {
    var token = new CancellationToken();
    var calls = new AtomicInteger();
    NoiseSampler2D sampler =
        (x, z) -> {
          if (calls.incrementAndGet() == 3) token.cancel();
          return x;
        };
    var result = new double[1024];
    Arrays.fill(result, -1);
    assertThrows(
        CancellationException.class,
        () -> sampler.sampleGrid(0, 0, 1024, 1, 1, 1, result, 0, 1024, token));
    assertTrue(calls.get() >= 3 && calls.get() <= 256);
    for (int i = 0; i < calls.get(); i++) assertEquals(i, result[i]);
    for (int i = calls.get(); i < result.length; i++) assertEquals(-1, result[i]);
    var polls = new AtomicInteger();
    var preflight = new CancellationToken(() -> polls.incrementAndGet() > 2);
    calls.set(0);
    Arrays.fill(result, -1);
    assertThrows(
        CancellationException.class,
        () ->
            sampler.samplePoints(
                new double[1024], new double[1024], 0, 1024, result, 0, preflight));
    assertEquals(0, calls.get());
    for (double value : result) assertEquals(-1, value);
  }

  @Test
  void compiledFieldBatchesMatchSingleSamplesAcrossThreads() throws Exception {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "batch";
    var item = ProcessingRegistry.create("EXPRESSION", "expression");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("source+(sin(x)+sin(x))*0.2+z*z"));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(n);
    var sampler = new CompiledProject(p).noise2D(n.uuid, 31);
    try (var pool = Executors.newFixedThreadPool(3)) {
      var jobs = new ArrayList<Future<?>>();
      for (int k = 0; k < 6; k++) {
        int start = k;
        jobs.add(
            pool.submit(
                () -> {
                  double[] xs = new double[128], zs = new double[128], out = new double[128];
                  for (int i = 0; i < 128; i++) {
                    xs[i] = -65 + i * .5 + start;
                    zs[i] = 32 - i * .25;
                  }
                  sampler.samplePoints(xs, zs, 0, 128, out, 0, new CancellationToken());
                  for (int i = 0; i < 128; i++)
                    assertEquals(
                        Double.doubleToLongBits(sampler.sample(xs[i], zs[i])),
                        Double.doubleToLongBits(out[i]));
                }));
      }
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void compiledBatchFailureRetainsTheStructuredExpressionSpan() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "failing";
    n.algorithm.key = "CONSTANT";
    var item = ProcessingRegistry.create("EXPRESSION", "sqrt");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("sqrt(x)"));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(n);
    var sampler = new CompiledProject(p).noise2D(n.uuid, 0);
    double[] result = {-9, -9, -9, -9};
    var batch =
        assertThrows(
            BatchSamplingException.class, () -> sampler.sampleGrid(0, 0, 4, 1, -1, result));
    assertEquals(1, batch.sampleIndex);
    assertEquals(-1, batch.x);
    var boundary = assertInstanceOf(EvaluationFailure.class, batch.getCause());
    assertEquals(n.uuid, boundary.resourceUuid);
    assertEquals(item.uuid, boundary.itemUuid);
    var span = assertInstanceOf(Expression.Problem.class, boundary.getCause());
    assertEquals(0, span.start);
    assertEquals(7, span.end);
    assertArrayEquals(new double[] {0, -9, -9, -9}, result);
  }
}
