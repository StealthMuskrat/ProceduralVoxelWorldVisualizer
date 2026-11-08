# Sampler lookup admission — unvalidated

W05-C09 covers direct sampler selection in specification 13.1.4, 13.3.3 and 13.8.1, with the exact visible-ID policy from 3.1.2/3.1.4. `CompiledProject.noise2D(String,long)` and `noise3D(String,long)` previously leaked `NoSuchElementException` for unknown names. UUID overloads already used `IllegalArgumentException` for unknown resources and dimension mismatches. Visible-ID misses now use that same typed rejection, with dimension-specific context.

Selection remains exact and confined to the requested dimension's namespace. `Field` and `field` may identify different resources, and the same visible name in 2D and 3D resolves independently to each resource's stable UUID. Lookup does not trim, normalize or case-fold the supplied selector. A decomposed spelling does not match a stored NFC ID. Null visible names reject as unknown selectors; null UUIDs retain their existing invalid-selector rejection. Successful visible lookup delegates to the existing UUID overload. UUID identity and dimension are now checked before constructing the selected field's NoiseCompiler; successful compilation keeps its existing private copy and executable behavior.

Compilation failures for a valid selected resource retain their existing policy. No new fallback, automatic repair, numerical operation, registry entry, public signature, model/schema format or editor dependency is introduced. The project preparation/validation contract and engine/world lifecycle are unchanged. The compiler-construction ordering is a source observation, not a measured speedup.

## Deferred definitions

Three `SamplerLookupTest` definitions cover missing/null/case/whitespace/decomposed/wrong-namespace names; absent/null/wrong-dimension UUIDs through both compiled and convenience compiler routes; exact aliases with independent coordinate arithmetic; and ownership across caller renames, value edits and resource removal. The alias test defines 324 raw-bit observations over both dimensions, three common names, three seeds, nine coordinates and two lookup routes.

`SamplerLookupConsumer` adds an engine-JAR-only replay under the existing `ProjectParseConsumer` command. It has no editor/render/native dependency and defines:

| Boundary | Deferred expectations |
| --- | --- |
| Initial admission | 16 visible-name rejections, six UUID rejections and six convenience-compiler rejections |
| Accepted selection | 486 raw-bit field observations across 2D/3D, three exact names, seeds −17/0/103, nine mixed-sign coordinates and UUID/visible/convenience routes |
| New snapshot after edits | Four rejected old/new aliases and four successful UUID/visible renamed-value controls |
| New snapshot after removal | Four rejected UUID/visible selectors |
| Retained samplers | 54 independent samplers, each checked again after caller renames, value changes and removal |

The 36 total rejection cases must throw `IllegalArgumentException`, so an unrelated exception does not count as a successful rejection. Fields use separate integer/quarter/binary-fraction expectations for constant + X + 2Z (+ 4Y) + seed remainder / 128; no generated chunk is used as an oracle. Caller JSON is compared before edits and after final removal. These are sampler-selection controls, not a replacement for full algorithm, dependency, batch, concurrency, world or acceptance verification.

A future successful run writes `project-parse/sampler-lookup/sampler-lookup-report.json`. The existing manual-only Linux/Windows commands already compile all example sources and invoke ProjectParseConsumer; no workflow change or dispatch is needed. Existing consumer report dimensions stay separate. No report has been generated.

## Status

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content/metadata checks add no runtime evidence. The observed baseline remains **922 tests / 120 suites** and no A01–A36, Windows 11 or release gate advances. The wider W05 sampler, region, cache and lifecycle audits remain open; publication remains pending on local main.
