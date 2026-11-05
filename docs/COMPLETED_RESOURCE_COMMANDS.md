# Complete-worker gates for resource browser commands

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. A03/A06/A08/A09/A22/A32, native/Windows 11 and final release acceptance do not advance.

## Readiness alone cannot commit a resource action

ResourceEditSession already retained a prepared resource command or captured worker failure until the current resource dialog permitted acceptance. Its readiness callback still dropped the task handle. That could permit document publication inside unfinished posting or before executor.execute returned successfully, concealing a subsequent handoff/admission failure behind an accepted edit.

The callback now only marks readiness and retains task observation. Acceptance requires that receipt, normal complete worker return and successful executor admission, alongside the existing admission-in-progress and final-publication guards, before taking a mailbox. Inline acceptance during posting or between inline task execution and successful admission leaves the same work version, project, private Properties input, raw seed ledger, preferences, revision, history and UI completion untouched. A later ordinary owner acceptance finishes the original action without another confirmation or automatic retry.

Captured worker failures and rejected command diagnostics wait for that complete boundary. A poster/admission failure cannot hide behind an unaccepted value or captured failure. Operational Future status/get faults retain their existing refresh recovery policy; not every operational fault waits for Cancel. The shared nonblocking helper contains status/get faults and preserves interruption from terminal reads.

## Retirement preserves newer gestures and retries

Refresh captures its original work token and command before Future observation. Acceptance retains its original identity across refresh and repeats the complete-worker check before taking a payload. Either terminal read may create a ready replacement gesture and return normally, throw Error or report cancellation; the old call cannot consume or fail the replacement.

Work retirement returns the identity reserved before cancellation callbacks. Preparation, rejection diagnostics and failure feedback check that exact identity before continuing. Failure feedback checks again after describing a Throwable, preserving a retry or closure created by its message getter. Work-only retirement retains the original gesture for explicit retry. Full retirement clears the old gesture's label, context, draft/selection and request ownership before cancelling its task, so a cancellation-created gesture owns all of its own metadata. Clear and staleness check the reserved identity before replacing progress or sending an obsolete unavailable completion.

New requests also recheck disposal and execution after pending-work observation. A terminal failure description that closes the controller cannot leave a newly written gesture in that disposed owner. Final close already marks disposal before task cancellation and keeps that ordering. Committed-task retirement, reconciliation and ordinary completion remain under the existing publication fence; cancellation callbacks cannot start another resource command while that fence is active. Consumed refresh/completion errors retain their existing reporting and cannot replay the accepted document transaction.

## Clean, Apply and Discard keep their document boundaries

| Entry | Complete acceptance publishes |
| --- | --- |
| Clean resource action | One command prepared against committed values. |
| Apply private Properties and resource action | One combined document transaction; private Properties validation still precedes running the command. |
| Discard private Properties and resource action | One command prepared against committed values; private input, including invalid provisional seed text, stays active until successful publication/reconciliation. |

Cancel, clear, stale/rejected/failed work and closure retain committed/private values and history. Exact project/source/lifetime, draft/raw/selection, diagnostics, checkpoint/history and captured validation-policy checks remain authoritative. Successful commands still reconcile selection and run one UI completion; an accepted no-op retains Redo. Save stays explicit and preferences remain separate. Command families, UUID/ID semantics, public synchronous constructors, model/API/schema/registry/project/package formats and deterministic field/voxel rules are unchanged. Engine/model/storage remain independent of editor/native dependencies.

## Deferred definitions and available review

Eighteen ResourceCommandWorkerCompletionTest definitions exercise clean commands, combined Apply and Discard with invalid provisional seed input. They cover real posting held after readiness and late posting faults with explicit retry; inline posting/admission with success or captured failure and subsequent faults; clearing/closing held success/failure; complete work without a receipt; contained terminal status/get/interrupted-read faults; either terminal read creating normal/Error/cancelled successors; failure descriptions retrying/closing; full-clear gesture metadata/progress; staleness preserving ready successors without obsolete completion; failure cleanup creating ready retries; final closure before cancellation-driven admission; committed-task retirement under the publication fence; complete rejection feedback and explicit retry; rejected-plan cleanup creating a ready retry; consumed refresh/completion reporting without replay; and closure during pending observation before a new request.

Retention checks compare complete project/private/preferences snapshots, raw ownership, revision and history, with no reconciliation or completion before publication. Success defines one revision, one completion and one undoable transaction, complete project Undo/Redo comparisons and independent .125/.625 constant-field expectations. Real-worker fixtures use executor completion barriers and release latches in finally blocks. One existing BackgroundResourceEditTest real-worker definition now waits for complete worker return before acceptance. BackgroundResourceEditProbe already waits through queued-task executor barriers. No definition or probe ran.

Existing native case/receipt counts, complete saved-model/schema comparisons, public formats/APIs, workflow wiring and independent replay dimensions remain unchanged. No new native case or generated-world fixture is added. Source review, git whitespace/content checks and repository metadata review are the available checks. Owner capture/comparison/history-installation/reconciliation/feedback costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
