# Field-driven sampling transform components

Implementation status: **awaiting validation** on `codex/development-phase`. Per the owner's 2026-10-28 instruction, no GitHub tests run during this development phase. This batch has not been compiled or tested, and adds no passing acceptance claim to the last validated main baseline.

Numeric placement drivers now support components of an individual ordered sampling transform. These are computational placement controls: they intentionally change world sampling and generated output. They are independent of camera and display settings.

| Destination | Component | Applicable transform |
|---|---|---|
| `transformX`, `transformY`, `transformZ` | Translation distance, scale factor, or rotation angle in degrees | TRANSLATE, SCALE, ROTATE |
| `transformPivotX`, `transformPivotY`, `transformPivotZ` | Sampling-space pivot coordinate | SCALE, ROTATE |

Each transform destination requires an explicit scalar noise resource and `transformIndex`, the zero-based authored index into the **resolved** `samplingTransforms` list. The editor displays this index as a one-based row number and shows the selected transform's kind and disabled status. It uses the same private Properties draft and Apply/Revert transaction as existing drivers. Row references are positional: reordering transforms changes their target; deleting a referenced final row makes Apply invalid until the driver is changed or removed. There is no hidden resource identity for a transform row.

All 64 authored transform positions retain their indices, including disabled rows. Drivers targeting a disabled transform are structurally validated but do not sample their fields, execute arithmetic or appear in an executed trace. Disabled driver rows also remain structurally validated. Translation pivot targets, missing/out-of-range rows, missing resources and nonfinite settings are invalid. An operation overriding its inherited transform list must retain all referenced positions or override its driver list as well.

## Evaluation order

Transform components participate in SAMPLING alongside placement scale/offset controls. Every sampling driver queries its field at the original resolved placement coordinates: signed axes, original component scale/offset, then the original enabled transform sequence. Driven controls never feed back into another sampling driver's query. Within the sampling phase, authored driver order is preserved. Each transform/component pair starts with its own resolved constant. REPLACE, ADD and MULTIPLY use the existing finite mapping contract, including immediate rejection of a nonfinite intermediate before a later row can replace it.

After the sampling phase, the primary field and all subsequent driver, interval, probability and gate queries use the completed placement controls and transform components. Translation, scale about a pivot, right-handed rotation in the declared axis order, and final 2D X/Z projection retain their existing order. Driven rotation angles are reduced modulo 360 before StrictMath sine/cosine evaluation, matching constant-angle behavior. Driven controls use bounded per-thread scalar scratch, do not mutate resolved settings and disable unsafe primary column reuse.

The demonstration fixture applies an original X offset of 1, drives the first transform's X translation from global Y, then drives a second transform's X scale to 2 and its X pivot to 1. The final primary X field is `1 + 2*(x+y)`, so the threshold 2.5 accepts exactly the integer cells with `x+y >= 1`.

## Persistence and traces

Project format remains 2. `NumericDriver.transformIndex` defaults to -1 for historical drivers and must remain -1 for placement-wide destinations. New transform destinations require an authored integer 0..63 and an existing row; the latter and transform-kind checks are semantic validation beyond structural JSON Schema. Null, coerced strings and fractions are rejected by the reader. The current schema and world-operation registry v5 publish all seventeen destinations, component metadata and index requirements. Schema files were updated alongside their generator; generator agreement awaits deferred validation.

Whole-list inheritance, portable dependency closure, replacement/remapping, explicit empty overrides and Reset to Layer Default retain their existing behavior. A driver index refers to a transform in the same resolved placement, never to a resource UUID. Compiled projects own their settings independently of subsequent authoring.

`GenerationTrace.ParameterEvaluation` adds nullable `transformIndex`; non-transform rows report null. The Inspector shows the one-based target row alongside the effective arithmetic and fixed baseline query. The old eight- and ten-argument constructors remain available; record-pattern/reflection clients must account for the added record component. Setting navigation still targets the owning resource's parameterDrivers property and does not store an array index as navigation identity.

## Deferred verification

Definitions added for twelve engine regressions, six model contracts and two editor transaction/export cases. They cover distinct/repeated targets, scale and rotation pivots, axis order, frozen baseline, disabled-row laziness, downstream fields, Y-dependent column safety, root/worker/trace agreement, finite failures without partial publication, maximum row bounds, inheritance and immutable compilation.

`TransformDriverConsumer` defines a standalone engine-JAR roundtrip/replay of 441 analytical cells across three root sizes. `verify_transform_drivers.py` independently checks the recorded cells, phase/row/coordinate trace and structural schema. The positive/negative fixtures join schema definitions, and future Linux/Windows workflow steps reference these consumers. None has been executed in this development batch. Full interactive control gestures, representative performance and Windows 11 hardware acceptance remain pending.
