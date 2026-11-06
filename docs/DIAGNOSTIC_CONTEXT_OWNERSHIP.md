# Coherent diagnostic context and captured input ownership

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending; no A01–A36 gate advances.

## Supplied identities constrain pointer inference

DiagnosticLocations preserved each explicit resource/control UUID independently, but filled missing fields from the pointer without checking that the resolved target agreed with the supplied identity. A diagnostic naming resource A could gain control B from a different noise at the pointer's array position. A diagnostic naming only control A could similarly gain resource B. A missing parameter key could also be inferred from that unrelated context. Reordering a candidate made these combinations especially misleading for source navigation.

Enrichment now requires every supplied resource/control UUID to match the pointer-resolved context before filling any missing resource, control or parameter field. Supplied UUIDs and parameter keys always remain exact. Fully explicit context survives stale pointers; partially explicit context keeps unresolved fields null when the pointer cannot prove agreement. Diagnostics with no explicit identities and agreeing partial context retain ordinary inference, including escaped input keys, nested operations, processing/transform/shared-parameter controls and curve points. Code, severity, message and original pointer remain unchanged.

| Supplied context | Pointer target | Missing-field behavior |
| --- | --- | --- |
| Resource A | Resource A / control A | Infer control and parameter normally |
| Resource A | Resource B / control B | Retain A; omit inferred control/parameter |
| Control A | Resource B / control B | Retain control A; omit inferred resource/parameter |
| Full explicit context | Any pointer | Retain supplied fields exactly |

Noise/membership UUID aliases now infer an owner only when that candidate resource actually exists. UUID lookup requires full hyphenated spelling, allowing either hex case, rather than Java's abbreviated UUID forms. Array lookup accepts ordinary nonnegative decimal indices exactly: 0/1/2 are positional tokens, while +0/00/01/-0 do not select an element. These are diagnostic lookup rules; serialized project UUID/schema formats already have their separate existing validation. Ancestor/parameter heuristics for diagnostics without explicit authority remain best effort. This is not a general JSON Pointer validator, proof of global UUID uniqueness in an invalid candidate, or repair of contradictory metadata supplied by a caller.

## Capture diagnostic rows before candidate access

enrich now copies its diagnostic list before serializing the candidate. A host collection accessed during candidate serialization can no longer clear/replace the rows being enriched. A RuntimeException during candidate conversion returns that same captured immutable list, rather than recopying potentially changed caller data. Null lists/elements reject before candidate access; an empty owned list returns without serialization. Returned rows remain immutable Diagnostic values.

The prior structural-validation rule remains: Validator does not ask enrichment to serialize an already-failed candidate. Normal completed validation still enriches its candidate. Model access and caller collections remain cooperative; direct Errors retain their existing behavior, and no callback isolation, blocking bound or atomic snapshot of a concurrently mutated project is promised. Public signatures/record components, project/archive/schema/registry formats, generation math and UI/preferences behavior remain unchanged. Model/engine retain their native/editor independence.

## Deferred coverage and independent replay

Seven DiagnosticContextOwnershipTest definitions cover conflicting explicit identities and preserved payload; agreeing partial context with escaped keys/uppercase UUID paths; resource/control reorder with stable full context; missing/abbreviated aliases and noncanonical/overflowing indices; caller-list mutation during successful/rejected candidate serialization; null/empty row ownership before candidate access; and ordinary Validator enrichment with unchanged authoring values. Existing six DiagnosticLocationTest definitions remain unchanged. None has run.

DiagnosticContextConsumer uses only public engine-JAR classes and is invoked by the existing ProjectParseConsumer. An explicit table defines **24 context cases**, plus **five reorder checks and two caller-list capture cases**. It covers exact supplied authority, matching inference, orphan aliases, canonical lookup, fixed payloads and immutable fallback rows. Restoring candidate order must restore complete authoring JSON.

Three compiled snapshots retain the original resource order, reversed resource order and reversed control order. Independent constant-source formulas .25 + x + 2z and -.5 - x + z define **90 exact field observations**. Roots 2/4 produce **six retained chunks/216 EMPTY-cell expectations** after all contexts/engines close. The original project parse, validation-result and other independent replay dimensions and native receipts remain unchanged. The future report is project-parse/diagnostic-context/diagnostic-context-report.json. No report was generated. Existing manual-only Linux/Windows commands already compile all example sources and invoke ProjectParseConsumer; no workflow command or trigger changes are needed.

Source review and git whitespace/content/metadata checks are the available verification. Remaining feature/failure audit, runtime/concurrency/stress validation, native/Windows 11 and release acceptance remain outstanding.
