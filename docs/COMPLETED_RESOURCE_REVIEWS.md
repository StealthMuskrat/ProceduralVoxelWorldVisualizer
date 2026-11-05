# Complete-worker gates for resource reviews and Import Apply

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, network update check or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending.

## Planning and transaction preparation have separate completion gates

ImportEditSession conflict planning and ExportEditSession catalog/dependency planning already retained delivered results for current review controls. Explicit acceptance could still install a result inside unfinished posting or executor admission. Final Import Apply additionally discarded its task handle on delivery, hiding a later handoff failure behind an accepted document transaction.

All three paths now retain task observation and require readiness, normal complete worker return and successful return from executor.execute before taking a mailbox. Receipt callbacks only mark readiness. Acceptance inside inline posting or after inline task return but before executor admission returns leaves the same request/version, previous rows/catalog and committed/private document state intact. Later owner acceptance completes the original explicit action without an additional confirmation or automatic retry.

| Path | Publication after the complete gate |
| --- | --- |
| Import conflict planning | Install the current choice/reload plan; the replacement remains unacknowledged until its rows are displayed. |
| Export catalog/dependency planning | Install the catalog/Included preview, retaining cached-catalog reuse; the replacement needs fresh display before Export. |
| Explicit Import Apply | Install one validated document transaction and its Undo delta, preserving separate Properties input and explicit Save. |

Captured planner/compiler failures and rejected Import candidates wait for the same complete boundary before their feedback can be adopted. Failed executor admission takes precedence over an unaccepted value or captured failure. Operational scheduler/handoff/Future-fault recovery retains its existing refresh policy; this is not a claim that every operational fault waits for Cancel.

## Original-request observation, retirement and closure

Planning and Apply refresh capture their original request before terminal Future observation. Acceptance rechecks that identity after observation and before any mailbox take. A terminal read that creates a ready replacement and then returns normally, throws Error or reports cancellation cannot consume or fail that replacement. Status/get faults remain contained by the existing nonblocking helper, and interrupted terminal reads preserve interruption.

Retirement returns the identity reserved before cancellation callbacks. Scheduling, preview installation, staleness and failure feedback check that exact identity instead of adopting a replacement's token. Failure descriptions are checked again before assigning/reporting feedback. If cancellation during old-plan consumption creates a ready newer plan, the old plan cannot install its rows over it. Planning and final Apply retain independent mailboxes; an obsolete planning description cannot consume/report against a ready Import transaction created during recovery.

Cancel marks the whole review closed before retiring either worker. Successful Import likewise marks the owner consumed/closed before cancelling the completed task and closing its mailboxes, then performs ordinary completion feedback. A cancellation callback cannot reload a review whose document transaction has already committed. Failed/rejected/stale preparation still retains its existing explicit retry/review requirements, and a committed Import cannot replay after completion feedback failure.

## Review, models and deferred definitions

Choice semantics, deterministic copy allocation within a choice session, new allocations on reload, desired selection, missing-selection handling, catalog caching, UUID/ID remapping and dependency closure are unchanged. A newly adopted plan still cannot authorize Import/Export until its own exact preview is displayed. Import planning itself changes no project/history/file; final Import remains one explicit resource transaction. Public synchronous paths and all model/API/schema/portable-package shapes are unchanged. No field or voxel rule changes.

Twenty ResourceReviewCompletionTest definitions cover:

- Real posting held after a receipt, normal complete adoption and one undoable Import; late posting failure retaining old rows/document and explicit retry; closure of held success or captured failure.
- Acceptance inside posting and executor admission, later poster/executor failure, captured planner/compiler failure feedback and complete work without a delivered receipt.
- Contained terminal status/get/interrupted-read faults; normal/Error/cancelled reads creating ready replacements; failure descriptions creating successors or closing owners; close before cancellation-driven admission.
- Old receipts while a successor is queued, blockers and newer private input before adoption, fresh-plan display before Export/Import and unchanged independent constant-field expectations.
- Rejected Import diagnostics awaiting complete handoff with explicit retry; consumed Import closed before cancellation-driven reload; old-plan retirement creating a ready successor; obsolete planning feedback creating a separate ready Import transaction.

The fixtures use real-worker latches and explicit executor barriers, with release in finally blocks. Their Export baseline is bootstrapped privately before testing later request boundaries, so constructor completion cannot supply acceptance authority for a subsequent action. Three existing real-worker definitions in ImportPreviewPlanningTest, ExportPreviewPlanningTest and BackgroundImportApplyTest now wait at all four planning/Apply acceptance boundaries. Existing native planning/Import Apply probes already complete their queued worker tasks through executor barriers. None of these definitions or probes ran.

Existing native case/receipt counts, complete saved-model/schema comparisons, public formats/APIs, workflow wiring and independent field/voxel replay dimensions remain unchanged. No native case or generated-world fixture is added. Engine/model/storage remain independent of the editor/native stack.

Source review, git whitespace/content checks and repository metadata review are the available checks. Owner snapshot capture/comparison/catalog/row/history/feedback costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
