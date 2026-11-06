# Malformed validation and diagnostic-result ownership

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending; no A01–A36 gate advances.

## Do not traverse a structurally failed candidate again

Validator.validate catches RuntimeException from structural traversal and records INVALID_STRUCTURE at /. It previously read that exception's message up to twice, then passed the already-failed candidate to DiagnosticLocations.enrich. Enrichment serializes the model to resolve UUID locations. A malformed in-memory collection could therefore be accessed again merely to describe its first failure; message access could itself throw or return different text before a usable diagnostic escaped.

The structural-failure boundary now captures one safe message. Null/blank/unreadable text uses the existing Null or malformed value fallback. Readable text is bounded to 2,048 UTF-16 units, including any truncation ellipsis, without splitting a surrogate pair; exact-limit and ordinary short text remain exact. Host toString is not called and the failure/suppressed list is not mutated. Errors thrown directly by traversal retain their existing propagation; this is a RuntimeException diagnostic boundary, not a broad VM-recovery handler.

After recording the structural diagnostic, validation returns an immutable copy of the diagnostics already collected. It does not serialize or retraverse that failed candidate for enrichment. Earlier diagnostic ordering, code/severity, messages and JSON pointers remain. UUIDs/parameter locations that would require re-enriching the malformed object are not inferred on that path. Ordinary completed validation still performs its existing enrichment. Repairing the candidate allows normal validation/compilation on the next explicit call.

This change covers the outer structural catch, not every model/compiler/JSON message boundary. Host collection/message implementations remain cooperative and may block or explicitly change caller state. Reentrant independent validation has its own validator instance. No callback isolation, preemption or unusable-VM recovery is certified. Rejected Save still stops before serialization, temporary output or replacement, preserving the destination through its existing validation-first contract.

## Result diagnostics belong to their result

ProjectIO.LoadResult and ProjectCompiler.CompileResult now copy diagnostics with List.copyOf in their compact constructors. A caller changing the original list can no longer rewrite a retained result or change LoadResult.success through that list. Returned diagnostics are immutable, and null lists/elements reject immediately with NullPointerException. Diagnostic values are existing immutable records.

The loaded ProjectData remains the same editable candidate; it is not deep-copied or frozen by LoadResult. CompileResult retains the same compiled snapshot identity. Existing success predicates and component/method signatures remain unchanged; no additional project/diagnostic consistency rule is introduced. Project/archive/schema/registry formats, deterministic field/voxel arithmetic, native dependencies and UI/preferences behavior remain unchanged.

## Deferred definitions and independent replay

Five ValidationFailureOwnershipTest definitions cover one collection access/message read across mutable, unreadable RuntimeException/Error, null/blank and bounded BMP/astral/exact-limit descriptions; retained partial diagnostics/order/paths after repair; nested independent validation with unchanged direct Error propagation; rejected Save preserving prior bytes and creating no staging files; and immutable LoadResult diagnostics with an editable project and null-ownership rejection.

Two CompileResultOwnershipTest definitions use the public compileWorld boundary to require one structural failure, no compiled result, healthy repair and continued use of an older compiled snapshot while the editable candidate is malformed. They also check copied successful/failed diagnostic lists, compiled identity and null ownership. Retained generated EMPTY chunks remain usable after closure. All seven added definitions remain uncompiled/untested.

ValidationFailureConsumer is invoked by the existing engine-JAR-only ProjectParseConsumer. Two validation entry points and eight message forms define **16 malformed-candidate cases, 16 candidate accesses and 16 message reads**. Every case retains a frozen INVALID_STRUCTURE / diagnostic, avoids enrichment retry, restores the original list, preserves complete authoring JSON, and successfully compiles the repaired candidate. Four constructor-copy checks cover successful/failed load/compile result ownership.

Independent source + x + 2z expectations use a constant .25 source. They define **16 retained-snapshot field observations while candidates are malformed and 240 repaired field observations**. Roots 2/4 produce **32 retained chunks/1,152 exact EMPTY-cell expectations** after engine/context closure. The original project parse/prepared/dependency/transfer/folder replays, native receipts and other independent dimensions remain unchanged. The future report is project-parse/validation-failures/validation-failure-report.json. No report was generated. Existing manual-only Linux/Windows commands already compile all example sources and invoke ProjectParseConsumer; no workflow command or trigger changes are needed.

Source review and git whitespace/content/metadata checks are the available verification. Remaining feature/failure audit, runtime/concurrency/stress validation, native/Windows 11 and release acceptance remain outstanding.
