# Typed values, field sampling, and coordinate stages

The editor, previews, World Generator, and public samplers compile the same saved noise definition. Values and fields have different selectors. A numeric `Earlier Result` reuses the memoized value at the current location. An `Earlier Field Prefix` reevaluates that earlier Scalar result and its dependencies at a new procedural location.

## Types and bindings

The six types are Scalar, Boolean, Vector2, Vector3, Coordinates2D, and Coordinates3D. Coordinate values are sample locations. Vector values are directions, displacements, or ordinary multi-component values; conversions must be explicit.

- Add/subtract accept matching Scalars or matching value Vectors. Multiply/divide accept Scalar×Scalar or Vector×Scalar and preserve the first operand's type.
- Equality compares every component of matching types, including coordinate values. Ordering comparisons accept Scalars.
- Select accepts a Boolean condition and two matching alternatives of any of the six types. Only the chosen alternative executes. AND/OR and expression Boolean/ternary operators also short-circuit across earlier-result dependencies.
- Compose Coordinates, Coordinates↔Vector, Scalar replication, Vector3→Vector2, Vector2→Vector3, and corresponding coordinate projections/expansions are explicit operations. Projection names identify retained axes; expansion names identify occupied axes and the Scalar input fills the remaining axis.
- Boolean↔Scalar uses explicit operations. Zero maps to false and nonzero finite Scalars map to true.
- Enabled rows are type-checked even if the selected output does not use them. Only demanded processing instructions execute, once per sample; unused invalid numeric branches do not execute. Valid pure constant results and constant selections are folded during compilation. Invalid constant arithmetic retains its runtime failure boundary.

## Coordinate stages

Transforms execute in stored order before sampling the source. Their controls may bind Constants, shared parameters, ambient values, earlier coordinate-stage results, or noise generator samples. Source and processing results are unavailable at this stage. Earlier coordinate results are snapshots: selecting an earlier location does not repeat earlier mutations. Post-source bindings select earlier processing results rather than coordinate-stage rows.

Translate, Scale, Rotate, and Domain Warp support explicit input Coordinates. Without that binding they use the incoming stage location. Scale and Rotate expose pivots. Angles are degrees; in X/Z, positive rotation turns +X toward +Z. Three-dimensional rotations apply the selected axis order; positive X turns +Y toward +Z and positive Z turns +X toward +Y. The legacy `angle` parameter remains the default Y rotation. Domain Warp exposes amplitude and optional independent Scalar displacement bindings per active axis, which may sample other generators. Unbound components use deterministic offset samples of the configured canonical warp algorithm.

## Sampled fields

Sampling rows select one of:

| Selector | Definition |
|---|---|
| Source Field | Primary algorithm plus this generator's coordinate transforms |
| Earlier Field Prefix | An enabled earlier Scalar processing result plus its upstream transforms and dependencies |
| Noise Generator Field | Another generator's selected output, referenced by UUID |

Default sample locations are incoming generator coordinates, before its coordinate transforms. The selected field applies its own declared transforms exactly once. An optional Coordinates input supplies the location explicitly. Offset/derivative/filter controls then modify that supplied location. Self/later/disabled prefixes, non-Scalar prefixes, and generator cycles are rejected before evaluation.

For cross-dimensional references, XY/XZ/YZ explicitly select retained axes. A 2D→3D reference inserts `slice` on the missing axis. A 3D→2D reference projects onto the selected plane. A 2D coordinate pair is stored in X/Z component order.

- **Offset Sample:** evaluate at location + offset.
- **Derivative:** centered two-point stencil `(f(p+h·axis)-f(p-h·axis))/(2h)`, with positive finite spacing and an active axis.
- **Smooth Filter:** a square/cube neighborhood, deterministic Y/Z/X iteration, integer radius 0..4, and positive spacing. BOX uses equal weights. GAUSSIAN uses `exp(-distanceSquared / max(0.5, radius²/2))`. Both normalize weights to sum to one. Strength in 0..1 blends the unfiltered center and filtered result. Values are not clamped.
- **fBm/ridged/billow/ping-pong:** 1..16 octaves, positive base frequency/lacunarity, explicit gain. Each octave reevaluates the selected canonical field at `p * frequency * lacunarity^i`, weighted by `gain^i`. Octaves retain that field's canonical seed; its UUID, project seed and seed offset determine the field seed. There is no hidden octave seed mutation or normalization. Ridged maps `v→1−abs(v)`, billow maps `v→2abs(v)−1`, and ping-pong applies a period-two triangular fold before accumulation.
- **Warp Sample:** deterministic displacement components generated from canonical noise and explicit amplitude/frequency/seed offset, followed by an actual evaluation of the selected field at the displaced location.

Compilation conservatively limits estimated sample work to 1,000,000 units, processing/transform dependency depth to 128 and generator-reference depth to 64. Nested filters/fractals multiply their upstream work; oversized plans fail with `COMPLEXITY_LIMIT` before allocating per-sample scratch. Runtime scratch is primitive and thread-local; compiled field prefixes are reused. These estimates are admission limits, not timing guarantees. Legacy saved sampling references in `parameters.noiseGeneratorUuid` remain readable; new controls persist a typed field binding.

Independent mathematical expectations, transform order, projections/slices, short-circuit failures, immutability, multithreading, and complexity rejection are exercised by TypedProcessingTest, CoordinateTransformTest, and FieldSamplingTest. Remaining compiler optimization and GUI acceptance work is tracked in DECISIONS.md.
