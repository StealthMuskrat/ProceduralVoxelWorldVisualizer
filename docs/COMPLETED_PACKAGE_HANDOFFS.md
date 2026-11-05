# Complete-worker gates for resource-package handoffs

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, network update check or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending.

## A receipt cannot open review or replace a package

ResourceImportLoader and ResourceExportWriter already retained delivered results until current File Location controls accepted them. Explicit acceptance could still run inside UI posting, or after inline work returned while executor.execute had not yet returned. A later posting/executor failure could then follow an installed Import review or an already replaced export destination.

Both owners now require readiness, normal complete worker return and successful executor admission before taking their mailbox. Delivery only marks readiness. An unfinished posting/submission boundary retains RUNNING and the request token, with prior destination bytes, private editor input and the absence of a new Import review. A later owning observation completes the original explicit action automatically; no additional confirmation or automatic retry is added.

| Owner | Protected publication |
| --- | --- |
| Package Import loader | Installation of one private conflict-resolution review. COPY/REUSE/SKIP review and explicit Import Apply remain separate before any document transaction. |
| Resource Export writer | Atomic replacement of the reviewed package destination, followed by consumption of that export review. |

Captured preparation failures and cancellations wait for the same complete gate before acceptance. Failed executor admission takes precedence over an unaccepted value or captured failure. Operational scheduler/handoff/Future-fault observation retains its current refresh policy; this follow-up does not claim every operational fault waits for Cancel.

Exact displayed-path mismatch still retires a ready request while posting is unfinished. Current opening, source/private/raw/selection/lifetime/checkpoint, reviewed-preview and blocker gates remain authoritative before review installation or replacement. Save As making the export target the active project while staging is held cannot authorize package replacement of that checkpoint. Public package serialization/validation and final active-project-path rejection are unchanged.

## Retired identity and final closure

Terminal observations are attributed to the request captured before Future status/get. The original identity is rechecked before any cancellation, failure or mailbox take, so an obsolete normal, failed or cancelled terminal read cannot consume/fail/cancel a ready successor. The existing helper contains status/get Throwable and preserves interrupted reads.

Cancel, stale input and failure move to their terminal phase before retirement invokes cleanup/cancellation callbacks. Retirement returns the identity reserved before those callbacks. Start and terminal feedback check that exact identity rather than adopting a callback-created successor's token. Failure descriptions are checked again before assigning/reporting feedback. A staging-cleanup description that creates a ready replacement cannot relabel it or attribute an obsolete primary failure to it; the cleanup event itself remains separately reportable.

Final close is visible before task cancellation and mailbox cleanup, preventing new admission during closure. Export's existing publishing fence remains: Cancel cannot interrupt atomic replacement, and close requested during replacement waits for consumption and then closes the owner. A completed replacement cannot replay. The shared ResourcePackageFiles.Prepared explicit cleanup-retry contract is unchanged; actor retirement remains best-effort disposal with separate cleanup feedback.

## Deferred definitions and remaining scope

Twenty PackageCompletionTest definitions cover:

- Real posting held after a receipt, normal complete adoption, late posting failure with retained document/file state and explicit retry; Cancel/close of success, failure and captured cancellation before a late posting fault.
- Acceptance inside posting and executor admission, later poster/executor failure, captured failure feedback waiting for completion and finished work without a delivered receipt.
- Contained status/get/interrupted-read faults; terminal reads creating ready replacements before normal/Error/CancellationException return; failure-message getters creating successors or closing owners.
- Path mismatch during unfinished posting, close visible before cancellation callbacks and old-receipt isolation while a successor is queued.
- Import completion opening only review before one explicit undoable Apply; a newer active-project checkpoint protecting the export target; export cleanup creating a ready successor during failure/Cancel/staleness; final close during atomic replacement; a new blocker before worker return with explicit retry.

Real-worker fixtures use latches and explicit executor barriers, with release in finally blocks. Two existing real-worker definitions in BackgroundResourceImportTest and BackgroundResourceExportTest now await a barrier before acceptance. ResourceImportLoadingProbe and ResourceExportWritingProbe now wait for their owner to leave RUNNING before their existing layout-settling frames; a fixed five-frame interval alone is no longer their completion proof. No new or adapted definition/probe ran.

Existing native case/receipt counts, complete saved-model/schema comparisons, public formats/APIs, workflow wiring and independent field/voxel replay dimensions remain unchanged. No native case or generated-world fixture is added. The actor fixtures retain complete project/private/raw/selection/history/diagnostic/preference ownership around package preparation/publication. Engine/model/storage dependencies and authored mathematics are unchanged.

Source review, git whitespace/content checks and repository metadata review are the available checks. Separate conflict-planning/Import Apply and other worker paths remain broader completion-audit scope. Owner capture/comparison/rename/cleanup/review/rendering costs, required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding; this follow-up does not certify repository-wide completion.
