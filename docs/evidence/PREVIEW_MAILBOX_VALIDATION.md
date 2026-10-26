# Versioned preview handoff validation

Validated implementation: `5170db1eadd46d943437abd948b3c2656e16b3ed`.

[Actions run 40420638477](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40420638477) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit contains 729 tests across 91 suites with zero failures, errors or skips; see [JUnit summary](preview-mailbox-junit.json). Seven PreviewMailboxTest cases cover latest ownership after consumption, pending-payload removal, token invalidation, monotonic request validation, closed-slot cleanup/rejection, a latch-controlled stale worker finishing after a newer payload, independent tokens, closure during sampling and supersession during an interrupt-clearing sampler callback.

The fixed-preview handoff has one slot. Advancing a request clears pending payloads and invalidates old tokens; only the current version can publish success or failure. A late worker cannot refill an emptied slot. Close clears pending data, cancels tokens through their parent predicate and rejects later publication. The UI retains its accepted image/mesh until a successful current preview is consumed. Future cancellation, queue purge, admission budgets and regeneration cadence remain unchanged.

Production 2D sampling now receives the request-scoped token. The concurrency test blocks an old field callback, advances the mailbox, allows the callback to return after clearing thread interruption, and observes CancellationException before row admission. No partial row is retained, and a new request samples correctly. Cancellation is polled at the existing row/value boundaries; it does not forcibly preempt an arbitrary blocking callback. The initial compilation path also checks the request token before compiling.

The workflow retains native editor/bundled/packaged smokes, image work-count/accepted-geometry/input checks, independent digests and 524,288 engine-only sample comparisons. This expands deterministic lifecycle/race coverage, not every scheduling interleaving or physical GUI/hardware stress scenario. Complete acceptance and production sign-off remain separate.
