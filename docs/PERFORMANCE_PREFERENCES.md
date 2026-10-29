# Performance preference editing — unvalidated development work

The Performance category now owns a complete private draft of the performance limits and the coupled default preview extent. Previously, each numeric control changed live preferences immediately. In particular, lowering Maximum preview dimension below the default extent made User Config invalid and prevented ordinary preference saves. The new workflow lets both fields be corrected together before publication.

All implementation, fifteen regression definitions, native workflow and independent replay definitions in this batch remain **uncompiled and untested** under the owner's development-phase testing pause. No acceptance gate or baseline test count is advanced.

## Input and publication

`PerformancePreferences` defines the fourteen owned fields, labels and inclusive integer bounds. The immutable complete value map includes worker count, maximum dimension, default extent, preview cell budget, cache, engine memory, streaming CPU/GPU memory, upload allowance, horizontal/vertical distance, LOD start/quality and resident chunk limit. Default extent must not exceed maximum dimension. User Config validation and generated structural field bounds use this catalog. Structural JSON Schema cannot express the comparison between two values; strict User Config semantic validation still performs it.

`PerformanceEditSession` retains exact raw text, including empty/intermediate signs, leading zeros and invalid entries. Inputs are bounded to 128 UTF-16 units per field; the native buffers have enough UTF-8 capacity for that complete text. Grammar is an optional sign followed by ASCII decimal digits, without whitespace, fractions, exponent syntax or hexadecimal. Conversion uses checked signed 64-bit parsing and field-specific bounds. In particular, the cell budget can round-trip 9007199254740993 and Long.MAX_VALUE without passing through floating point or a 32-bit widget.

Feedback is an immutable cached candidate/problem map. Invalid input never reuses an older valid candidate. Apply checks the complete exact displayed text, all owned active values and current validity. Changes elsewhere to any owned limit or default extent make the private draft stale until explicit Revert. Ordinary category navigation, Settings close/collapse and Save Preferences retain/exclude pending performance text. Unchanged Apply does no file I/O.

Apply merges only the fourteen fields into the latest User Config snapshot, validates it, writes through the existing forced-content/required-atomic-replacement writer, then publishes the owned values. Latest appearance, layout, shortcuts, manual update trust, profiling preference and unrelated editor/visualization fields are retained. A failed write keeps active limits, exact private text and cached candidate for retry, with bounded `PERFORMANCE_SAVE_FAILED` feedback in the shared Error Center. Reentrant Apply/edit/Revert during the synchronous save is refused.

The strict preference reader now rejects strings, nulls, fractions, signed-64-bit overflow and out-of-range values in these fields before Jackson can coerce or narrow them. Historical missing fields still inherit defaults. Format version remains 1; existing valid serialized integer settings remain compatible.

## Runtime behavior

Worker count and engine memory take effect at restart; the engine is not replaced by a preference edit. Default extent also takes effect at the next launch, when the three viewports are constructed. The panel compares these three saved values against its launch snapshot and shows pending restart details. Visualization settings link to the same coupled editor rather than exposing another immediate default-extent input.

Other applied limits are read by the existing preview/admission/streaming paths. Changing settings does not itself regenerate a fixed preview, change its requested extent, discard a complete accepted display or start a profiler capture. Existing failed fixed previews require Regenerate after correcting the request/budget. Existing volume-streaming limit-change and pressure behavior is retained. This batch does not replace those runtime policies or certify their timing.

A limit is an allowance, not an allocation. Zero cache and cell budgets below the default preview's requirements are valid intentional restrictions. The panel explains that a low cell budget may refuse the fixed 256 x 256 2D image or default cubic 3D extent. It never silently increases a budget or clamps a paired extent. Engine/model/project formats and numerical generation behavior are unchanged. No project transaction, Undo entry, private Properties publication, browser launch or update request is performed by these settings.

## Deferred verification scope

`PerformanceEditSessionTest` adds fifteen definitions covering coupled correction, invalid provisional strings, exact long extremes, every inclusive field boundary, immutable/cached feedback, complete displayed-text checks, atomic write-failure retry and unrelated preference merging, every stale owned field, ordinary-save exclusion, intentionally low budgets, strict JSON input, historical defaults, owned copies, reentrant saves and project/engine identity.

`PerformancePreferencesProbe` defines real ImGui text/mouse input through the production panel. It checks coupled rejection, intermediate `-`, periodic-save privacy, hidden/collapsed retention, stale-base Revert, an occupied-directory save failure and explicit retry, canonical display, restart notices and the original engine/private project input. A fresh panel constructed from saved values checks that a new host has no pending restart notice. It does not launch a second process or exercise a real engine restart.

`scripts/verify_performance_preferences.py` independently checks actual saved JSON integers and exact values, the existing structural/shortcut contracts, excluded periodic-save values, latest unrelated settings and temporary cleanup. Positive/negative fixtures and future Linux/Windows workflow definitions are included with development trigger exclusions unchanged. None has run. The native definition uses an isolated ImGui context without an OS window or renderer; physical DPI, full desktop scrolling, arbitrary filesystem failures, actual worker/memory load, performance measurements and the full GUI/hardware matrix remain pending.
