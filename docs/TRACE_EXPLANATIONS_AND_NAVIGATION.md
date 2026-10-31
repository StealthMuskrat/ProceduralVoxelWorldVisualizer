# Trace explanations and guarded source navigation

The Voxel Inspector and retained Generation Trace entries in Logs use one presentation component. Both show snapshot-owned names, ordered operation summaries, actual comparison values, setting provenance, executed gate/driver rows and bounded primary-noise observations. A trace is an explicit low-volume diagnostic replay, not an always-on profiler.

## Headless observation contract

`NoiseCompiler.explain(uuid,x,y,z[,limit])` and `CompiledProject.explainNoise(uuid,seed,x,y,z,limit)` run the canonical compiler/evaluator against its owned source snapshot. The default observation limit is 256 and valid limits are 0..4096. A completed `NoiseTrace` has the sampled result, immutable events and an omitted-observation count. Reaching the limit stops retention, not computation. Numeric failure still throws the normal structured exception; a failed trace does not publish a partial successful result.

An explanation compiler installs hooks at compilation time. Ordinary sampler instructions contain no trace hook or event allocation. Explicit replay can compile and allocate diagnostic buffers; it is intended for inspecting individual coordinates, not bulk generation throughput.

Events preserve resource and item UUID, snapshot ID/kind/type, evaluation coordinates and immutable typed values. SOURCE records actual source evaluation. TRANSFORM records the resulting coordinate value, with its incoming generator location. PROCESSING records a successfully materialized instruction after its dependencies. FOLDED records a retrieved optimized literal; it does not invent runtime evaluation of folded-away dependencies. OUTPUT identifies the requested resource's selected output. Successful sharing may omit a second materialization, and disabled/unexecuted branches produce no processing event. Nested sampled prefixes/references retain their own procedural sample locations and resource identities. This is not a complete expression-instruction/input-read trace.

World `trace()` uses these same canonical primary-field values and includes their observations in `Details.primaryField()`. A shared 1024-event retention budget applies across the ordered operations; later operations still compute normally after it is exhausted. Gates, strength/probability drivers and numeric parameter fields retain their existing faithful evaluated-value records and source links; their internal noise instruction streams are not included.

`Details.comparisons()` records effective minimum/maximum Y tests and the reached interpretation comparison: sampled field against the mapped threshold, or world Y against the rounded mapped height. Existing gate, previous-state predicate, interval and deterministic probability outcomes identify later rejection. Rows not reached are not fabricated.

`Details.settingTargets()` maps each setting to its actual owner UUID, logical property and override flag. Absent overrides continue to inherit live; trace creation never bakes them. All old Details/Step constructors remain available with empty added metadata. Record-pattern/reflection clients must account for the extended Details shape. GenerationTrace/Step now defensively copy their outer collections as well as the existing Details collections.

## Native workflow

Trace Coordinate still accepts any signed integer XYZ, including EMPTY. Ordered operation summaries and observation rows are clipped, with one expanded step and one selected row. Search is cached for the selected snapshot/step/query. Selected values use 4096-unit pages that preserve surrogate pairs, with full-value copying; headers and summary rows have bounded visible text. Copy Trace Details copies the same structured text from Inspector or Logs.

Go To Control targets resource/item UUIDs and logical property keys. Layer and placement links reveal the current property container, including inherited setting origins; nested gate/driver/interval rows never replay historical array indices. Noise links reveal the source algorithm, selected output or exact processing/transform UUID.

Every captured target has a fingerprint of its authored resource in the source snapshot. Before requesting navigation and before resolving a pending guard, the editor checks project identity, current resource presence and unchanged committed target definition. Deleted/changed/foreign targets retain their historical values but reject navigation with a retrace message. Same-resource private edits retain their object and text; cross-resource edits require Apply/Discard/Cancel. Failed Apply preserves the draft. Navigation, searching, copying and retention do not commit a project transaction.

Inspector, Logs, Notifications and Profiler source actions share the same guarded navigation service. The existing Find and private-noise reference guards retain their established workflows. Opening the placement's source also uses a guard.

## Retention and limits

Each successful inspector trace publishes a project-scoped informational Generation Trace event. Textual history retains its existing bounded grouping. Detailed trace payloads are a separate oldest-first store of at most four snapshots and an estimated 8 MiB of presentation data. Oversized payloads are not retained in Logs; the current Inspector still shows its successful result. Evicted details leave the textual event and an explicit retrace instruction. A new session/close clears detailed payloads. The estimate is an admission bound for retained presentation data, not an exact JVM heap measurement.

Independent numerical tests, immutable/source/stale checks, bounded clipping and real native navigation/Cancel/Apply checks provide scoped evidence. Full interactive Inspector/Logs recovery, all expression-input routing, representative hardware and Windows 11 acceptance remain separate.

The unvalidated [Find/Trace workspace follow-up](FIND_AND_TRACE_LAYOUT.md) replaces fixed shared-trace list heights with measured budgets, keeps copy/link/page actions persistent and owns source decisions by exact snapshot/step/row/query. New definitions remain unexecuted; physical and complete GUI acceptance remain separate.

The unvalidated [Find/Trace footer decision follow-up](UTILITY_DECISIONS.md) adds complete footer collection, Escape suppression and exact raw query/page/document/native-host action scopes. Trace clipboard failures are contained, and accepted source guards also check the document lifetime. The new four regression definitions, eighteen native cases and independent replay remain uncompiled/untested under the owner pause.
