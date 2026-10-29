# Application profiles and future-item defaults

Application Settings now edits a private profile with searchable, clipped Presentation and Creation Defaults lists. Presentation rows edit labels, tooltips, multiline help, group/order and display aliases for fixed choice keys. Creation rows select an explicit override or the built-in value, with typed controls, immutable ranges and option lists. Apply validates and writes before activating the profile; Revert reloads the active profile; Stage Built-In Profile stages a reset for explicit Apply. Changing a profile never dirties the project or commits/reverts its property draft.

## Immutable contract and creation scope

`CreationRegistry` is a versioned editor registry. Its keys, types, ranges, built-in values and valid choices are not app-config fields and cannot be edited through metadata. Its numeric bounds are creation UI limits, often a useful subset of engine-valid values; they do not narrow the project loader or existing engine numeric domains. Signed resource seed offsets retain the full exact 64-bit domain. Defaults validate finite scalar/vector values, integral values, Boolean types, vector widths, choices and known keys. Curve domain/display, remap endpoint and clamp ordering constraints are checked together; configured expression text receives canonical type/parser validation in both resource dimensions. This validates signatures and structure, not every dynamic mathematical domain a future graph could execute.

| Future user-created item | Supported profile values |
|---|---|
| 2D/3D Noise | Independent algorithm choice and exact seed offset; per-dimension/per-algorithm parameters such as frequency, fractal controls, cellular variants and shape sizes |
| Processing | Registry-declared constant input ports with their original types; selected numeric/Boolean/enum/text parameters, including expressions, remapping, terraces, shapes, sampling and filters |
| Coordinate transform | Dimension-specific translation/scale/rotation/pivots/rotation order and domain-warp values |
| Voxel Type | RGB channels in 0..255 |
| New Curve processing item | Domain, endpoint values, display range, interpolation and outside-domain policy |

Only the explicit editor creation factories consult these defaults. They construct fresh identities, copy arrays/nodes and store concrete supported values in the resulting project definition. Existing editing controls retain their canonical fallback values; opening an old item, resetting an existing curve, changing algorithm selection, loading a project, duplication, clipboard/import and engine compilation do not look up new-item defaults. A new layer still starts NO_CHANGE, and placements keep absent overrides/live inheritance. The normal empty project remains empty.

The UI uses fixed algorithm/processing/transform keys even when display labels differ. Algorithm choices and supported numeric parameter labels/tooltips consume presentation metadata, and help/settings lists consume grouping/order. Option aliases change text only; they cannot add a new choice or alter signatures. Processing execution order never follows metadata grouping/order. Labels/groups are bounded single-line strings; hash sequences are escaped for ImGui display identity while persisted text stays intact. Control descriptions can contain line breaks.

## Profile format and persistence

App Config remains format 1. `controls` can provide a subset of known controls, and `creationDefaults` is an optional sparse map keyed by immutable creation-control IDs. Historical labels/help merge with missing built-in definitions and new presentation fields. Explicit empty option maps/empty groups remain authored values. Unknown control/default IDs, unknown fields or choice aliases, invalid types/ranges, null structures and unsupported versions reject strict reads. Normal startup safely falls back to built-ins on malformed metadata. Reads are bounded to 8 MiB and do not rewrite the source file.

Applying a private metadata draft checks its base against the current profile, validates it, serializes a separate snapshot to a unique temporary sibling, forces content and requires atomic destination replacement. Unsupported replacement fails without a non-atomic fallback. Only success publishes the live profile. Validation/write failure preserves the active metadata; stale drafts must Revert before another Apply. Temporary output is cleaned where possible. As with other staged writers, device failure and filesystem durability beyond supported atomic replacement are not certified.

The application-profile schema and `editor-creation-controls.json` are generated from the same Java definitions. Structural schemas describe known names/types/ranges and fixed choice keys; runtime validation supplies cross-field and expression semantics. Positive historical/custom-profile fixtures and a negative immutable-control fixture accompany them.

## Separation and evidence

Metadata lives outside ProjectData, project history and the headless artifact. Saving/reloading a created project preserves its concrete values without requiring an app-config file. The independent `CreationDefaultsConsumer`, compiled against only the engine JAR, checks created 2D/3D arithmetic, a linear curve, a translated gradient, voxel colors and the empty world against independent mathematical expectations. The profile reference also checks presentation aliases, restart, atomic apply and existing project/draft/revision/history/numeric invariance.

Tests cover canonical algorithm agreement across dimensions, typed creation boundaries, fresh UUIDs and mutable-node ownership, import/duplicate preservation, invalid/cross-field defaults, historical merge, UI identity escaping, copy/order independence, strict file parsing, stale drafts and failed replacement. Native desktop smoke renders an overridden future-value draft and verifies that it never publishes implicitly. These checks do not certify all creation gestures, every metadata control interaction, every mathematical input domain or the complete A01–A36/Windows 11 hardware matrix.

[Creation-default input ownership](CREATION_INPUT_OWNERSHIP.md) adds retained per-control raw text, hidden-error/displayed-buffer publication guards, atomic vectors, explicit value recovery and cached/clipped profile rows on the development branch. Its twelve new regression definitions and native/independent definitions remain uncompiled/untested under the owner testing pause; they do not extend the earlier evidence above.

The unvalidated [shared configuration writer](UPDATE_TRUST_AND_PREFERENCE_FILES.md) adds bounded opened-stream reads and a matching serialized write limit, atomic replacement without fallback and retained cleanup failure context. New deferred definitions have not run.
