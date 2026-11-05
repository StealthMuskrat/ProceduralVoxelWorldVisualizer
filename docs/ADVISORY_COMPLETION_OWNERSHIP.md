# Advisory completion ownership for Find and Expression feedback

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, Python/schema checker, packaging or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. A11/A12/A22/A32/A33, Windows 11 hardware and final release acceptance remain pending.

## Delivery and display observation

Committed Find and inline/expanded Expression feedback are advisory displays. They continue automatically from their original search/check request and add no confirmation. Previously their UI completion callbacks also adopted the result. A live-filter/source callback could close the owner or prepare a successor during that adoption, after which the predecessor could retire or replace the successor. Immediate adoption also discarded task observation before an inline UI handoff or executor could finish and fail.

UI delivery now marks readiness only. Existing owner observations adopt ready work automatically: Find search/status/current-row/index observations and Expression observe/view. Adoption requires successful executor admission, a normally completed worker FutureTask and the delivered request's identity. A callback running inline inside the handoff cannot authorize a result before the handoff returns; an executor that runs a task then throws cannot authorize it before admission returns. These checks are nonblocking. A delivered task still returning may require a later owner observation; there is no measured frame or latency guarantee.

Both success and captured worker failure live in a retiring CPU mailbox. Find extends its existing owned slot to failures; Expression adds one slot for its immutable semantic request/result or failure. Queued UI callbacks retain request/epoch identifiers rather than full result, index or Throwable payloads. Cancellation, supersession, suspend/clear, failure and close invalidate that slot independently of draining callbacks. Late worker publication is rejected; it does not enqueue a completion into a retired owner. Existing worker-local captures and the bounded executor still have costs.

A prepared payload is consumed once. A same-request status read during its own adoption cannot take that slot again. Nested adoption of a different request remains possible and independently owned. Task detachment precedes accepted adoption, avoiding cancellation of the accepted worker. Because observation requires normal worker completion, handoff faults cannot silently populate the accepted index or expression cache.

## Callback and source fences

Find captures its original request/source before reading the live filter, then rechecks them and complete committed values after that callback. A callback may clear, close or cancel Find, commit/mutate a source, or build/adopt a ready successor. The predecessor cannot install its index, retire the successor or report its old failure against newer work. An outer observe also stops if callback work changed its source or desired request. Existing source-build reuse through typing and query-only supersession remain.

Current-row matching separately captures accepted source/filter/request identity before its live-filter callback, then rechecks those identities, document lifetime/revision and current filter afterward. Closing or replacing the search during that callback returns no navigation authority. A callback failure belongs to its original owner; throwing after creating a successor cannot fail the successor. Previous-row inspection and guarded navigation remain separate.

Expression captures the original request around source and clock callbacks in observe, retry and supersede, and around source reads during adoption. It rechecks complete existing Owner identity and a freshly projected semantic request before displaying a result or captured failure. A callback returning predecessor text, successor text or throwing after creating a successor cannot revive a closed/suspended owner, replace the successor's feedback, add the predecessor to its cache or poison the successor with an error. Source/clock callback failure reports only against the original request.

Existing semantics remain: 150 ms quiet intervals, first visible check, exact semantic cache keys, cache entry/estimated-byte limits, Unicode UTF-16 spans and UTF-8 selection, ambient shadowing and named-input routes. Find still keeps one source build across typing, uses its accepted index for later scans, preserves explicit scope bypass and accepted-filter reuse, and requires explicit Retry Search or changed filters after failure. Merely delivered work does not change pending presentation identity; adoption does.

Display checking still never commits, saves, applies or discards private edits. Expanded Use Text may stage incomplete text independently of feedback, and parent Properties Apply remains the transaction. Project/history/preferences, headless APIs, registries, serialized formats and authored mathematical results are unchanged. Owner snapshots/projections/comparisons, row rendering, navigation and private staging still cost UI time; provider, native, performance, physical input, DPI/OS and Windows 11 acceptance remain outstanding.

## Deferred definitions and replay

Eighteen AdvisoryCompletionOwnershipTest definitions cover:

- Delivery-only identities, no presentation/source callback at delivery, immutable accepted display and unchanged committed state.
- Inline handoff and executor faults, including status observations before those calls return; no accepted index/cache on failure.
- Find callback close/clear/cancel for success and failure; ready successors returned through old/new/throwing callbacks; complete source changes during callbacks and explicit retry.
- Accepted-row matching after lifecycle changes, self-status reads during adoption and ready failure retirement without reporting.
- Expression source close/suspend, ready successor adoption through old/new/throwing callbacks, initial capture and Retry closure, and clock closure during observe/supersede/retry.
- Self-view during expression adoption, superseded/closed captured failure, and a real worker finishing successfully or failing after close without queuing a payload.

Four prior definitions are adapted: the two actual-worker definitions explicitly wait for the worker task to finish before asserting adopted feedback; the expression cache-bound loops explicitly observe delivered results; and semantic selection captures its owner after feedback adoption. No definition ran.

BackgroundFindProbe adds inline-handoff-retry to its original nine native cases. The injected handoff first runs delivery then throws, requiring failed display without rows, no unchanged-frame retry, explicit Retry Search and a later ordinary result with the existing private navigation guard. Receipts require the new observed-failure flag and exact primary failure counts for scheduler, external cancellation and inline-handoff recovery. Existing queued/undelivered holds and project/private/raw/history/preferences checks remain.

BackgroundExpressionFeedbackProbe adds an inline-handoff-retry case beside its existing scheduler and handoff failures. It requires explicit checking retry, successful current feedback, and later expanded Cancel rejecting a held completion. The checker now requires seven cases and exactly one primary failure for each retry case. Existing Unicode, current-span, pending Use Text, Escape and closure cases remain. Synthetic injection and controls are definitions, not observed native or physical-input evidence.

Find's independent checker now defines ten complete before/after document/preference pairs. FindDebounceConsumer expands its background replay to **twenty saved projects, 840 constant-field observations and 2,880 EMPTY cells**, retaining .125 for field-0/needle, signed coordinates and root sizes 2/4. The added case contributes two unchanged projects, 84 fields and 288 cells. The original debounce replay remains 486 fields/288 cells. Expression's existing saved arithmetic fixture and independent consumer dimensions remain unchanged; the new expression case adds only a native receipt.

Existing manual-only Linux/Windows commands already reach the expanded probes/checkers/consumers. No workflow change or dispatch is required. Source review and git whitespace/content checks add no compilation, test, performance, native or release acceptance evidence.
