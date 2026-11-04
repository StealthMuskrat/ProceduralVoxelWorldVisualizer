# Background Expression feedback

Development implementation; **uncompiled and untested** under the owner's testing pause. No Gradle, compiler, JUnit, native probe, consumer, Python replay or GitHub workflow was executed for this batch. The last validated baseline remains **922 tests / 120 suites**. This is not A01–A36, Windows 11 hardware or release acceptance evidence.

## Authoring behavior

Production Desktop inline Expression controls and Expanded Expression now prepare display-only parser/type feedback on the existing bounded editor worker executor. The first visible expression starts immediately. Later text or semantic input changes retire older work and wait for a 150 ms quiet interval; unchanged frames do not resubmit compilation. The UI displays waiting/checking status rather than retaining an error, input route or statistics from different text/types/constants. Returning to a cached semantic input can show its feedback immediately.

Failed scheduling, terminal worker failure, external cancellation and rejected UI delivery leave an explicit **Retry checking** action. Unchanged frames do not automatically repeat a failed request. Editing supersedes old feedback normally. Failures report a bounded application event without changing project diagnostics or correction input.

**Use Text** is independent of parser completion and validity. It stages exactly the shown expanded buffer into the existing parent Properties item while checking is pending or unavailable, then retires that opening's work. Incomplete text can still be staged for correction. Cancel/Escape discards the expanded buffer; Properties Apply remains the full binding/resource/compiler validation and committed transaction. Feedback never applies, saves, changes preferences or creates Undo history.

Parser spans remain zero-based, end-exclusive UTF-16 positions. Existing native selection translates them to UTF-8 bytes. A changed semantic input also changes the selection owner, even if the source string is identical, preventing an old parser range from being applied after binding/type correction. Inline source-link focus waits for current feedback; subsequent user text, pointer or navigation input retires that deferred focus intent. Focus requests remain one-shot.

## Ownership and work boundaries

`ExpressionFeedback.capture` creates an immutable request from source, selected result type, named input types and resolved constant/shared/ambient values. It retains the existing named-input versus parser-span diagnostic distinction, constant shadowing and scalar/Boolean semantics. The worker receives only that request and runs the existing `Expression.compile`; it never reads mutable project, Noise, Item or expanded-buffer objects. Successful results contain immutable input names and compiler statistics. The synchronous public feedback/session constructors remain compatible for existing callers and probe definitions.

`BackgroundExpressionFeedback` owns pending work and its cache on the UI thread. Publication checks the exact project object, document lifetime/UUID/revision, selected resource/siblings, parent draft and processing item identities/UUIDs/kind/membership, and a fresh semantic request including the current source. New or same-UUID Open, draft refresh, removed items, changed inputs and superseding work cannot authorize predecessor publication. A normally completed future alone grants no display authority; an explicit UI completion must pass those checks. Duplicate callbacks are consumed once.

Inline and expanded editors have independent actors. One inline processing item is expanded at a time in the existing item browser. Hiding/collapsing inline controls cancels that actor and clears its cache; closing Properties also closes any expanded session. Cancelling queued work removes it from the production ThreadPoolExecutor where possible; running parser work is bounded by existing expression limits and late completion is ignored.

Each actor caches at most 128 semantic requests and an estimated 2 MiB of retained key/result data. Large requests may be displayed without being cached. Cache access is UI-owned, never shared with compilation workers. This is a conservative retention estimate, not a measured JVM heap limit.

Only compilation moves to workers. Input resolution, immutable request projection/comparison, selected-item membership checks, UI rendering, expanded opening/staging ownership snapshots and ordinary final transaction publication remain on the owner thread. The quiet interval and bounded executor do not establish a measured latency or throughput guarantee.

## Deferred verification definitions

- Sixteen `BackgroundExpressionFeedbackTest` definitions cover real worker separation/held UI delivery, immutable capture, ambient shadowing, typing coalescing, stale completion, shared values/result types, Unicode spans and named-input routes, scheduler/worker/handoff failures and explicit retry, externally cancelled tasks, stale retry, refresh/removal/New/same-UUID Open, cache entry/byte limits, suspend/close, pending/failed Use Text and semantic selection invalidation.
- One `HelpCatalogTest` definition covers discoverable guidance, explicit retry and independent staging without metadata mutation.
- Six `BackgroundExpressionFeedbackProbe` cases run inside the existing `ExpandedExpressionProbe` context when testing resumes. They use production inline/expanded widgets with held queues and a synthetic monotonic clock: queued/undelivered frames, inline Unicode correction/coalescing, expanded current-span correction and pending Use Text, scheduler retry, handoff retry/late Cancel, and Escape/panel closure.
- The existing `verify_expression_selection.py` definition requires the six-case background receipt in addition to the original saved-project/schema assertions. Existing manual-only Linux/Windows probe/replay wiring reaches these definitions without adding a workflow trigger or dispatch. The established independent expression consumer and saved arithmetic fixture retain their original scope.

These are definitions, not observed passes. Full compilation, replay, large-document measurements, physical keyboard/DPI behavior, broader GUI/platform/Windows 11 testing and final packaged deliverables remain pending.
