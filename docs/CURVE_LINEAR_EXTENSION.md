# Finite Linear curves and guarded Extend — unvalidated development

This main-branch source batch supports specification 8.2.4–8.2.5, 8.3.1–8.3.5, 8.4 and 8.6.2, with deferred A09/A11/A22 expectations. No compilation, JUnit, native probe, public consumer, checker/reference evaluator, schema generator, packaging or workflow ran. The owner testing pause and last observed validated baseline of 922 tests / 120 suites remain unchanged. Source coverage and future replay definitions do not advance acceptance status.

## Endpoint representation and ordinary compatibility

A Linear segment with finite endpoints -2^1023 and 2^1023 previously failed construction because its endpoint difference overflowed. Its midpoint and quarter points are finite. ScalarCurve now records an exceptional Linear segment as its immutable endpoints plus a per-segment flag. The unused polynomial coefficient remains finite; ordinary Linear segments retain their original coefficient and Horner order. Exact knots return stored endpoint bits before interpolation.

Only the flagged segment, or a failed Linear Horner result, evaluates the exact endpoint line. It reuses the package-private FiniteMapping integer-ratio backend: finite doubles become exact integer units of 2^-1074, the complete affine numerator is formed, and quotient/remainder rounding produces the nearest binary double with ties to even. No endpoint difference or normalized weight must first fit in a double. This avoids admitting infinite coefficients or clamping output values.

## Extend recovery

Ordinary extension retains endpoint + (value-origin)*slope, including its historical rounding and signed-zero behavior. Exceptional Linear extension uses the endpoint line when its difference, derived slope, offset, product or final sum is nonfinite. A nonflat Linear slope rounded to zero also selects that line: a curve from (0,0) to (2^1023,Double.MIN_VALUE) must produce -Double.MIN_VALUE at -2^1023 rather than flattening to zero.

The entire exceptional affine expression is rounded once. For endpoints (0,Double.MIN_VALUE) and (2^1023,2*Double.MIN_VALUE), extension at -2^1022 is exactly half the smallest subnormal and rounds to positive zero. Performing a rounded tiny product followed by endpoint addition would instead give Double.MIN_VALUE. This is why the zero-slope recovery calls the exact line directly rather than retrying ordinary remap arithmetic.

Step and valid Smooth/Bezier extension keep their existing endpoint derivative. When finite operands produce a nonfinite offset, product or sum, FiniteMapping forms the exact tangent expression endpoint + (value-origin)*slope and rounds its complete rational result. This can recover a finite cancellation after an overflowing product or offset. A zero tangent with a failed offset returns the stored boundary value; a numerically flat Linear exceptional extension does likewise, retaining a stored negative zero. Exact nonflat cancellation produces positive zero; negative nonzero underflow retains its sign through the shared ratio conversion.

## Boundaries and metadata

Domain and display spans must still be finite and positive. Smooth/Bezier secant and coefficient representability checks are unchanged. This does not make every mathematically finite cubic representable, recover arbitrary polynomial intermediates or alter Repeat/Mirror reduction. Clamp/Repeat/Mirror, Step knot ownership, source authoring data and immutable compiled ownership keep their existing contracts. True output overflow remains nonfinite from ScalarCurve and reaches the existing structured executing-item failure in NoiseCompiler. Previously invalid or incorrect extreme Linear curves can now generate corrected values; project files and existing exact archives are not rewritten.

CurveModes and the tracked format-1 curve registry add linearArithmeticPolicy guarded-linear-and-extension-v1 and its shared description. The earlier guarded-harmonic-and-coefficients-v1 descriptor is unchanged. Central curve Help uses both descriptions. Mode IDs/order, defaults, public method signatures, project format 2 and schemas are unchanged. No renderer, native input transaction, dependency or profile/control setting is added. The exceptional ratio path allocates BigInteger values; ordinary interpolation does not use it, and no cost measurement ran.

## Deferred definitions

Twelve CurveLinearArithmeticTest definitions cover 17 manually specified hexadecimal cases (75 direct sample identities), both compiled dimensions, opposite/maximum finite endpoints, microscopic domains with infinite derived slopes, wide-domain zeroed slopes, nearest-even subnormal ties, flat signed-zero extension, explicit tangent product/offset cancellation, ordinary raw-bit parity, all outside modes, unchanged cubic/domain/display rejection, real output overflow, immutable source ownership and concurrent sampling. CurveModesTest extends canonical/tracked metadata expectations; one additional HelpCatalogTest definition covers searchable policy guidance without metadata mutation. None has run.

CurveWrappingConsumer additionally invokes the engine-only CurveLinearConsumer under its existing output-directory argument, retaining wrapping and cubic arithmetic replays. Seventeen cases across 2D/3D define 34 saved field projects and 900 exact direct/grid/point observations before/after strict public save/load. Destination padding, complete authoring values and saved bytes must remain unchanged. A separate saved World uses the recovered opposite-endpoint curve with threshold zero: 300 occupied/EMPTY observations span X/Y=-2..2, Z=-1..1, inclusive Y=-1..1, roots 2/4 and original/reloaded projects.

The new verify_curve_linear.py defines an independent Fraction straight-line/Hermite/tangent oracle, raw-bit comparison with all 75 hand expectations, 35 saved-project schema/value checks and exact replay-report dimensions. Its Linux/Windows command definitions are added to the existing manual-only workflow without changing triggers or dispatching. The vectors were written directly; no evaluator generated or checked them.

Only source inspection, Java formatting and git diff --check are performed for this batch. Wider numerical/native-preview behavior, allocation/performance, complete integration, physical Windows 11 hardware and final independent artifact/release acceptance remain pending. Main remains the sole branch.
