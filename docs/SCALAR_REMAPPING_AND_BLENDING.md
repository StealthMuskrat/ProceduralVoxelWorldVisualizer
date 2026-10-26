# Scalar remapping and weighted combinations

The canonical processing registry now describes REMAP, CLAMP, TERRACE, FALLOFF and BLEND controls, types, dimensions and semantics. Processing registry version 4 adds these descriptions and BLEND; project format remains 2. Loading and evaluation never consult future-item defaults. Properties uses the same immutable compiled manipulation plan for compact previews; normal compiled fields, public API, references, World placements and diagnostic replay share one compiler.

## Weighted combinations

BLEND takes required Scalar `a` and `b`. Both accept the existing typed bindings, including other generators from the canonical source registry, shared parameters and earlier Scalar results. An optional Scalar `weight` input overrides the stored numeric weight (default 0.5). The mode selects a target:

| Mode | Target |
| --- | --- |
| LERP | b |
| ADD | a+b |
| SUBTRACT | a-b |
| MULTIPLY | a*b |
| DIVIDE | a/b |
| MIN | min(a,b) |
| MAX | max(a,b) |

The result is `a + (target-a)*weight`. RAW weight permits signed extrapolation. CLAMP explicitly restricts weight to [0,1]. RAW output preserves signed doubles; CLAMP_01 explicitly clamps the result. There is no automatic normalization of source values. Weight zero evaluates only a; LERP weight one evaluates only b. Other full-weight modes evaluate the target directly, avoiding an unnecessary blend difference. Nonfinite target arithmetic, including division by zero, fails at the executing item before output clamping. Unselected branches retain normal lazy evaluation and typed validation.

## Falloff

Historical absent controls mean COORDINATES domain, EUCLIDEAN metric, LINEAR shape, CLAMP outside policy, center zero and extent 64. This retains the original signed radial formula. Default coordinates are transformed definition coordinates. An optional `position` input must be Coordinates2D (X/Z) or Coordinates3D (X/Y/Z); vectors are not implicitly converted.

COORDINATES measures distance from the authored center using EUCLIDEAN, CHEBYSHEV, MANHATTAN or AXIS. AXIS uses the selected active axis. INPUT instead measures `abs(a-centerX)` independently of the sample position. Extent must be positive and finite. Two-dimensional evaluation ignores stored centerY; axis Y is invalid in 2D. Known inactive settings remain stored, and supported active controls have exact diagnostic keys.

For `t=distance/extent`, CLAMP holds t in [0,1], while EXTEND retains t. LINEAR uses t; SMOOTHSTEP uses `t²(3-2t)`; SMOOTHERSTEP uses `t³(t(6t-15)+10)`; COSINE uses `(1-cos(pi*t))/2`. The output is `a*(1-shape(t))`, with no output clamp. Extended polynomial/cosine shapes follow these exact formulas outside the unit interval, including overshoot or oscillation. They are deliberate explicit extrapolation, not a monotonic continuation guarantee.

## Remap, terrace and authoring

REMAP preserves affine evaluation order and supports descending input/output endpoints. Input endpoints must differ. Clamp Output now uses the sorted output endpoints, so descending output ranges work with the explicit clamp enabled. Historical ascending ranges retain their numerical behavior. CLAMP requires min <= max at compilation rather than failing only when sampled. This stricter invalid-input rejection is intentional.

TERRACE retains uniform levels per unit, including negative values and values outside [0,1]. For cell `floor(a*levels)`, transition occupies the final authored fraction of each interval and applies smoothstep. Zero transition produces a left plateau, exact knots start the next interval, and transition one smooths the entire interval. There is no output clamp. Existing broad runtime level counts remain supported; creation presets still cap at 65,536.

Properties provides searchable mode choices, slider/text pairs, explicit field-driven weight and typed position switches, and compact transfer plots. Plots evaluate 65 points over input [-1,2]; falloff uses unit input and X in [-extent,2*extent], Y/Z zero. Plots respect authored centers/domain, are display-only, and report invalid or undisplayable results without changing the draft. Slider gestures explicitly choose within creation preset ranges; drawing wider valid text values does not clamp them. Changes remain private until one parent Apply and one Undo entry. Precise diagnostics can reveal a hidden inactive setting for repair.

The independent Python corpus covers 548 signed/descending/shape/mode vectors. Native and engine-only probes use a cross-dimensional reference, blend, falloff, remap and terrace chain: original value 0.5 at (2,3); an actual weight edit to 0.75 and searchable ADD selection yields 3.25. Validation runs after the full batch is implemented. Full Tree 6 warp controls and complete GUI/hardware acceptance remain separate.
