# Preview request retirement before cancellation — unvalidated

W06-C11 covers render/UI handoff ownership in 1.4.1/14.1.3/14.1.5 and obsolete-preview suppression/retry in 15.5.2/15.5.3. Viewports.mark previously called the GPU backend before advancing its PreviewMailbox epoch, detaching the worker and settling active/dirty state. A throwing candidate deletion could leave the old worker eligible to publish. Future.cancel callbacks could also admit a successor before later old bookkeeping cleared its job/active state. previewJobFailed had similar cancellation-before-settlement ordering.

Both paths now capture the old worker/renderer, detach the job and pending-volume owner, advance the epoch/mailbox and settle UI state before foreign notifications. Mark preserves the caller's force intent and leaves the new request dirty; terminal worker failure consumes dirty/force intent and installs its retry message. World changes advance Inspector view validity and clear the picked bookmark before notifications, capturing only the old view-bound Inspector ticket. Coordinate-only traces retain their existing independent lifetime.

Cleanup attempts the captured renderer's candidate cancellation, captured Future cancellation, the still-current captured pick and executor purge independently. GPU cancellation runs before worker callbacks can stage a successor; later callbacks never look up and cancel v.job. A changed Inspector ticket prevents old view cleanup from cancelling a replacement trace. The first cleanup fault may receive PREVIEW_CANCEL_FAILED only for the still-current epoch/renderer and settled owner state. Worker failure reports its primary PREVIEW_FAILED separately, before cleanup feedback. Guarded text checks before/after reading protect reentry; delivery failures cannot overwrite viewport state. Original Throwables are not mutated or linked through suppression.

Inspector cancellation also clears its global source/validity/display/retry owners, request invalidation and feedback before its Future callback. It attempts task cancellation and purge independently, then preserves the original first notification fault for existing callers to handle. A new coordinate request admitted by that callback keeps its context and serial; no late old field clear or cancelCurrent follows it.

The bounded callback/reporting primitive is shared as PreviewNotifications. VolumePreviewHandoff's observation/report entry points delegate to it with the existing contracts, avoiding another notification queue or fault-retention owner. An admitted callback batch is attempted once, with captured references and no automatic replay; a callback may have completed its own side effects before throwing. Notification callbacks remain cooperative and may block their notifying thread. A failed Future.cancel need not stop physical execution, but the retired mailbox version rejects its late result even if interruption is ignored or cleared.

## Deferred definitions

Eight PreviewCancellationTest definitions use actual PreviewMailbox, InspectorRequests/InspectorTask and VolumeTextureUpload with independent CPU texel storage and controlled Futures:

- GPU cancellation faults before/after deletion and Future faults before/after cancellation state, requiring retired mailbox/token/UI ownership before either notification, independent cleanup attempts, unchanged original failures and later healthy exact texture completion.
- GPU cancellation reentry into close or successor admission, preserving the new worker/candidate while still attempting the old captured Future and avoiding stale text/feedback.
- Terminal RuntimeException/Error primary failure with cleanup faults, requiring primary diagnostic priority, consumed retry flags, rejected old publication and retained exact samples.
- Primary message reentry into close/successor admission, with no stale primary/cleanup event or old status overwrite.
- Inspector cancellation callback admitting a coordinate trace, retaining its new ticket, source context and valid request serial despite the old cancellation fault.
- New Inspector admission during GPU cleanup, with captured-ticket rejection preventing another cancellation of that new trace.
- Explicit Inspector invalidation before its Future callback, preserving the callback's new coordinate request while leaving unrelated preview work eligible.
- A real held worker that clears/ignores interruption, released after GPU/Future cancellation faults and current publication; a shared-executor barrier requires its obsolete publish to fail and the current payload to survive.

Independent x + 10y + 100z + base + 0.25 expectations compare retained/replacement texture arrays. Faulting-before-release backends may retain physical residue; fixture disposal clears that fake residue rather than claiming successful hardware deletion. The tests exercise notification helpers with a CPU owner model, not actual Viewports/native widgets. Caller state ordering and captured-ticket wiring are source-traced. Existing fifteen handoff, eight volume-failure, six display-gate and native/project/optics definitions retain their expectations; no report or new native probe is added.

## Status and limits

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content checks add no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, exact math, budgets, UUID/source mappings, schemas, registries and authored formats remain unchanged. Source remains on local main with publication pending.

This addresses fixed/volume preview request invalidation, terminal Future recovery and associated Inspector cancellation bookkeeping. It does not finish source-compiler/scene admission, general Viewports.close/GL teardown, other representation acceptance, all instrumentation or complete leaf/caller inventories. Actual native worker/camera/retry/close behavior, physical input/DPI, callback overhead and representative resource/Windows 11/release verification remain open; W05/W06/W07 audits stay incomplete.
