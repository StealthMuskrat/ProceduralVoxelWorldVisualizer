# Placement sampling, type mappings and field drivers

A placement references an ordinary reusable noise definition. These world-use settings alter the placement's sampling and interpretation, while the reusable definition retains its own transforms, processing and seed offset. The fixed inheritable-property vocabulary is exported in `registries/world-operation-registry.json` and declared in SettingsResolver.

Each scalar or axis/scale/offset component overrides independently. `samplingTransforms`, `typeIntervals`, each driver object, the field-gate tree and the voxel-predicate tree replace atomically. Presence wins even for zero or false; resetting removes the override and restores live inheritance. Moving a placement to another layer preserves overrides. Apply, save and undo remain document transactions.

## Sampling coordinates

First select each sampling component from signed X, Y or Z world axes. Duplicate axes and projections are deliberate and allowed. Apply its component scale and offset, then execute the enabled `samplingTransforms` array in stored order. Translation adds XYZ. Scale subtracts the pivot, multiplies components, then adds the pivot. Rotation uses right-handed degrees, subtracts the pivot, rotates current coordinates about each fixed sampling-space axis in the declared XYZ permutation, then restores the pivot. Angles reduce modulo 360 before StrictMath trigonometry. Programs execute sequentially rather than reassociating authored floating-point operations into one matrix.

The resulting coordinates enter each input resource's canonical definition. A 2D resource consumes transformed X/Z with Y=0; a 3D resource consumes XYZ. Gate and driver references use the same placement program, preserving their own dimensionality. Project min/max Y and height comparisons refer to original world Y. Coordinate overflow fails with operation/resource/world-coordinate diagnostics before a chunk publishes.

A 2D field can now vary with world Y through its axis bindings or sampling transforms. Only the provably Y-independent component form uses the X/Z column cache. Ordered transform programs conservatively evaluate per voxel, including programs whose mathematical matrices cancel but whose floating-point rounding can still retain Y dependence. Direct samples, traces, stage chunks and region generation all use this same rule.

## Type sources and interpretations

`FIXED` uses `voxelTypeUuid` for SET. `INTERVALS` searches finite, nonempty ranges with independent `includeMin`/`includeMax` flags and UUID type targets. Overlapping intervals are rejected, including a shared endpoint claimed by both ranges. Singleton intervals are allowed only when both endpoints are included. Stored order affects presentation and trace indices; valid non-overlapping ranges produce the same type after reordering. A sample matching no interval produces NO_CHANGE, preserving previous committed state even when the requested action was CLEAR.

`TYPE_SELECTION` accepts the primary field without a threshold and requires INTERVALS. `ALL` accepts every sample within effective Y bounds, useful for parameter-only fields. Existing HEIGHT_BELOW/ABOVE/BOUNDARY and SELECTION/DENSITY meanings are preserved. Any interpretation can additionally use interval type selection; field gates and previous-state predicates still apply before committing SET/CLEAR/NO_CHANGE.

## Strength and probability drivers

Each driver is explicitly enabled and chooses the primary placement field (null reference) or another ordinary 2D/3D noise UUID. Input endpoints must be finite and strictly ordered. Values clamp to that range before LINEAR or SMOOTHSTEP mapping. Output endpoints must lie in [0,1]; reversing them creates a decreasing map. SMOOTHSTEP uses t²(3−2t). Scaled subtraction handles finite opposite-sign endpoints whose full span would overflow.

The final probability is:

`constant probability × constant strength × probability-driver factor × strength-driver factor`

A disabled driver contributes 1. Drivers are evaluated after interpretation/type selection, before field gates. They do not modify the shared resource or the configured constants. The final draw remains keyed by world/noise/placement seeds, stable placement UUID and integer world XYZ; transformed sample coordinates do not replace the hash coordinates. Probability 0 never commits; probability 1 always passes.

## Field gates and inspection

COMPARE supports GE/GT/LE/LT/EQ/NE. INTERVAL has independent endpoint flags (historical documents default to both inclusive). AND/OR short-circuit in stored order. NOT requires exactly one child. PROBABILITY uses its configured [0,1] probability and seed offset, a separate deterministic stream, and the stable placement identity plus gate path. Moving a probability gate within its tree deliberately changes that path and its random stream. Its seed is `mix(operationProbabilitySeed XOR 0x6a09e667f3bcc909 XOR mix(gateSeedOffset) XOR mix(JavaStringHash(gatePath)))`; draws use the existing high-53-bit CoordinateHash XYZ mapping. The root path is `fieldGates` and child paths append `/index`.

GenerationTrace details expose transformed primary coordinates, matched interval index/candidate type, each executed driver's sample/map/constant/effective factor, final probability and deterministic draw. Gate reports include only executed branches and include separate gate draws. Existing trace constructors remain available. The Inspector resolves type names against the traced snapshot.

`fixtures/world-use.json` and the independent engine Example demonstrate Y-bound 2D sampling, ordered translation, interval type selection, a mapped driver, NOT/probability gates and owned region collection. New settings/gate fields are optional in the format-2 structural schema and receive the documented defaults when absent. `fixtures/world-probability-vectors.json` records independently derived seed/hash/draw vectors. No project format version changed. Semantic validation is still mandatory; structural JSON Schema cannot express every cross-reference or interval-overlap constraint.

This batch does not certify every Tree 10 GUI scenario or add general field-driven thresholds/arbitrary parameter destinations. Strength/probability are the explicit driver destinations implemented here.

## Development-branch control navigation

[World-setting control focus](WORLD_SETTING_CONTROL_FOCUS.md) adds one-shot activation for layer/placement settings, singleton field drivers and root conditions, with inherited-override and conditional-control fallbacks. It is implemented but uncompiled/untested under the owner GitHub testing pause. The earlier numerical/semantic evidence above does not validate this UI extension.
