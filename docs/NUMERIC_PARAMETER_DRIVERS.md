# Numeric placement parameter drivers

Layer defaults and placement overrides now include an ordered `parameterDrivers` list. Each row maps a primary or explicitly referenced scalar field into one of three documented destinations: `threshold`, `baseHeight` or `heightScale`. The entire list inherits live by property presence; an explicit empty override disables the inherited list, and Reset to Layer Default removes that override. Editing, reordering and removing rows stays provisional until Apply commits one transaction.

A NumericDriver extends the existing FieldDriver authoring shape with `destination` and `combination`. It retains enabled, optional noiseGeneratorUuid, inputMin/inputMax, outputMin/outputMax and LINEAR/SMOOTHSTEP mapping. A null source uses the already sampled primary value. Explicit resources use their own 2D/3D dimension and the placement's signed axes and ordered sampling transforms. The primary field's column cache does not cache a separate Y-dependent driver. FieldDriver now permits the canonical NumericDriver subtype; probability/strength settings retain their base FieldDriver shape and existing semantics.

## Exact execution contract

After effective Y clipping and primary sampling, applicable enabled rows execute in stored order before field interpretation, type interval selection, field gates, previous-state predicates and probability. Threshold rows apply only to DENSITY/SELECTION. Base-height and height-scale rows apply only to HEIGHT_BELOW/HEIGHT_ABOVE/HEIGHT_BOUNDARY. Disabled and irrelevant rows are not sampled and do not appear in executed trace reports. Rows still require valid finite structural settings and resolvable authored references, including disabled rows.

The current value begins with the resolved constant setting. Each row computes its mapped scalar and then applies:

| Combination | Result |
|---|---|
| REPLACE | mapped |
| ADD | current + mapped |
| MULTIPLY | current × mapped |

Repeated destinations are allowed and ordering matters: starting from 1, ADD 2 then MULTIPLY 3 gives 9; reversing those rows gives 5. There is no reassociation or implicit clamp after combination. A nonfinite intermediate fails that executing row even if a later replacement could hide it. The failure identifies the placement, world XYZ and zero-based authored row. Failed/cancelled generation never publishes a partial chunk.

Inputs clamp at the declared endpoints. Input minimum must be strictly below maximum. Interior LINEAR uses normalized t; SMOOTHSTEP uses t²(3−2t). Numeric outputs can be any finite signed endpoints, including descending or equal endpoints. Interpolation avoids overflowing opposite large endpoint spans, returns exact declared output endpoints for clamped inputs, and bounds rounding to the convex output interval. Constant endpoints return that constant. REPLACE/ADD/MULTIPLY then operate in ordinary ordered double arithmetic and require a finite result. Probability/strength FieldDriver outputs remain restricted to [0,1], and their established multiplication order is unchanged.

Height interpretation uses the resulting baseHeight + primary × heightScale, then the existing FLOOR/CEIL/ROUND policy. Density/selection uses primary >= the resulting threshold, including exact ties. General parameter rows do not change primary samples, type interval input, gate thresholds, probability, strength, coordinates or seeds. These three destinations are the current explicit whitelist; arbitrary reflective destinations and field-driven coordinate transforms are not supported.

## Traces, persistence and resources

`GenerationTrace.Details.parameters()` is an immutable list of ParameterEvaluation records: authored row, destination, combination, optional noiseUuid, sample, mapped, previous and effective values. Inapplicable/unreached rows are omitted, and row numbers retain their authored positions. The inspector displays these reports alongside probability/strength and inherited constant settings. Existing seven- and eleven-argument Details constructors remain available with empty parameter reports; record-pattern/reflection clients must account for the added component.

Project JSON stays format 2. parameterDrivers is optional and defaults to an empty list when reading historical projects. Each row's structural schema, destination/combination vocabularies and the 32-row limit are published in the schema and world-operation registry v3. References participate in dependency closure, portable copy/remap, replacement, deletion and undo just like other settings references. Compilation freezes the resolved list; subsequent authoring changes do not mutate existing compiled worlds or snapshots.

`Example.parameterDriversDemo()` and `fixtures/parameter-drivers.json` provide an engine-only height example driven by an explicit 3D X field followed by an ADD row. The independent consumer checks exact y <= x+1 results, JSON roundtrip and trace values. Normal startup still creates no resources.

## Validation scope

Tests cover independent mapping expectations, extreme spans, descending/constant outputs, whitelist/range/ref/row-count rejection, atomic inheritance, ordered repeated destinations, exact threshold ties, height rounding, explicit 2D/3D and transformed Y sampling, cache safety, unused/disabled/out-of-Y laziness, overflow without publication, stage snapshots, frozen compiled settings, worker/root/trace invariance and resource import/replacement/undo. Native smoke adds the actual ordered numeric controls using a saved valid demonstration project. Broader parameter destinations, per-driver column optimization, representative hardware performance and full interactive Tree 10 acceptance remain outside this extension.
