# Grid coordinate bits — unvalidated development

This source follow-up supports specification 13.3.1/13.3.2/13.3.4 and independent headless integration A22. No compiler, JUnit, consumer, checker, native probe or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain.

## Coordinate ownership

The primitive grid traversal previously evaluated every axis as `origin + index * step`, including index zero. IEEE addition can turn a supplied `-0.0` origin into `+0.0` when the computed step term is positive zero. Public sampler implementations can observe that distinction, so the first grid sample could disagree with a direct sample at the supplied origin. A zero step could lose that sign throughout an axis.

Both grid dimensions now use the same axis rule:

- At index zero, pass the supplied origin unchanged.
- At every index with a `+0.0` or `-0.0` step, pass the supplied origin unchanged.
- Otherwise evaluate `origin + index * step` with separate multiplication and addition, retaining existing advancing-axis rounding.

Axis endpoint preflight uses the same rule. Finite-input requirements, overflow rejection, X-fast/Z-row/Y-plane traversal, destination offsets/strides and untouched padding remain. No per-sample objects are introduced. This does not introduce fused arithmetic or overflow recovery. Arithmetic on an advancing axis can still produce a signed zero according to normal Java double operations.

Legacy uniform-step overloads delegate to this traversal. Point batches already pass coordinate array values directly and require no change. BatchSamplingException receives the exact coordinates passed to the failed sample; earlier successful values remain written, and the failed slot and tail remain untouched. Cancellation and complete preflight before sampling retain their contracts. Public signatures, project format and authoring state remain unchanged.

## Deferred definitions

Eight GridCoordinateBitsTest definitions cover independent signs for 2D/3D origins, each row/plane origin, either zero-step sign, direct/point equivalence, destination padding, advancing-axis rounding, subnormal cancellation, raw failure coordinates and successful-prefix ownership, invalid preflight and cancellation before writes. The advancing arithmetic case requires positive zero for `-1 + 10 * .1`, where fused multiply-add would give a different nonzero result.

GridCoordinateConsumer uses only the public engine JAR. Its matrix projects every coordinate axis independently with all signed-zero origin combinations and per-axis steps `+0.0`, `-0.0`, `+.25`, `-.25`. Exact quarter-unit expectations are constructed independently from integer positions. It defines 768 2D and 12,288 3D grid observations, each compared bit-for-bit with scalar and point sampling. Nonwritten grid cells and point prefix/tail padding must remain unchanged. Separate rounding and failed-sample coordinate/cause/prefix cases are included. The future report is `grid-coordinate-report.txt` under `build/independent/grid-coordinates/`.

Future Linux/Windows commands and artifact paths are added to the existing manual-only workflow without dispatch. These definitions do not establish compiled-expression, full world determinism, performance, platform or Windows 11 acceptance. Main remains authoritative; no development branch was created.

The subsequent [batch failure-detail follow-up](BATCH_FAILURE_DETAILS.md) safely freezes one bounded readable cause description without replacing failed-sample metadata, coordinates or prefix ownership. Six added module definitions and a separate 32-case engine-JAR replay remain uncompiled/untested. The replay defines 48 successful prefix values/336 untouched cells in 384 failed-batch destination cells plus 16 healthy arithmetic observations/48 destination cells. GridCoordinateConsumer invokes it beneath batch-failures while retaining the original coordinate report and dimensions. The testing pause and observed baseline remain unchanged.
