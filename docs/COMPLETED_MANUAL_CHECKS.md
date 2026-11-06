# Complete-worker gates for manual checks

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, Python/schema checker, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending.

## Delivery, worker completion and executor admission

ProjectValidationSession and UpdateCheckSession already retained delivered results until current owner controls accepted them. Their direct asynchronous APIs could still accept inside the UI handoff itself, or after an inline task returned while its executor admission call had not returned. A later handoff/executor failure could then be lost behind an already installed diagnostic list or exposed release result.

Acceptance now requires three independent conditions: the request's UI readiness receipt, a normally completed FutureTask and successful return from executor.execute. Receipt callbacks only mark readiness; they no longer call owner refresh. An acceptance attempt made inside either unfinished boundary leaves RUNNING, the request token and previous diagnostics/no release link intact. The same original request finishes automatically from a later owning observation after those boundaries succeed; no additional confirmation or automatic retry is added.

A queued UI callback may be processed while its worker is still returning from posting it. That race no longer authorizes a result. A blocked handoff remains cancellable, and a subsequent handoff failure preserves prior rows or the absence of a reviewed URL. A task that completes inline before its executor throws is likewise rejected. This **supersedes the earlier exception allowing explicit publication inside an inline callback before later handoff/executor failure** in the ready validation/update notes.

Captured validation/fetch failures also wait for complete handoff and admission before owner acceptance can report them. Operational failed/cancelled Future recovery retains its existing UI refresh policy; this change does not claim that every operational failure waits for Cancel. Current source/trust invalidation, current-control ordering, Escape suppression/cancellation, hidden/collapsed completion and explicit retry keep their existing separate panel policies.

## Safe observation and reentrant feedback

Both sessions now use the existing BackgroundFailure.completed helper for nonblocking terminal observation. isDone and get both lie inside its failure boundary. RuntimeException/Error from either method becomes one owned recoverable failure instead of escaping the workspace. Interrupted terminal reads preserve the caller's interrupt flag; successful completion alone still grants no publication authority. The original request is rechecked after the observation before any mailbox take, so observation cannot consume a replacement request's slot.

Failure handling captures its retired request identity before reading Throwable.getMessage. A message getter that starts a ready successor cannot overwrite that successor's pending message or report the predecessor against it. Existing reporter-created successors remain independently owned. Final close marks the owner closed before cancelling work and closing the mailbox, retaining rejection of late receipts/publication.

Document/lifetime/revision/complete-source/diagnostic identity and value checks remain unchanged for validation. Exact applied endpoint and ordered allowlist, metadata/version/URL validation and reviewed-result identity remain unchanged for updates. Neither readiness nor automatic successful publication opens a browser or copies a link. Update transport rules, manual-only startup, model/schema/registry/public APIs, saved formats, project/history/private inputs/preferences and field/voxel mathematics are unchanged.

## Deferred definitions

Fourteen ManualCheckCompletionTest definitions cover:

- Real-worker handoffs held after posting a receipt: normal validation/update completion, failure after receipt and explicit cancellation before a late failure.
- Explicit acceptance attempted inside the handoff and again after the task returns but before executor admission returns.
- Captured validator/fetch failures retaining private feedback until complete handoff/admission.
- Future status/get Error and interrupted-read containment for both owners; the original queued computation is explicitly cancelled before the task-boundary test override.
- Failure-message getters creating ready successors without predecessor feedback or mailbox contamination.

The real-worker definitions use latches and explicit executor barriers, not a fixed frame delay. Fake update fetchers return owned metadata; no transport, browser or clipboard boundary is invoked. None of these definitions ran.

Four prior definitions are adapted. ReadyValidationTest now rejects an inline acceptance attempt followed by executor failure and retains previous diagnostics. Three UpdateCheckRecoveryTest definitions now require a reporter-created successor to wait for completed delivery, reject a poster fault after an inline acceptance attempt, and reject later executor failure for both successful and failed fetches without reporting twice. An executor admission failure takes precedence over a captured fetch value/failure that could not yet be accepted.

Existing thirteen-case validation background and 59-case update native receipts remain applicable with unchanged UI controls/counts, saved-model comparisons and independent engine replay dimensions. No native case or generated-world fixture is added by this follow-up.

Source review, git whitespace/content checks and repository metadata review are the checks for this batch. Repository changes are retained on main. This adds no compilation, runtime or acceptance evidence; all owner-thread projection/reporting costs, broader feature/completion audit, native/Windows 11 and final release gates remain outstanding.

## Retirement and source-capture follow-up — unvalidated

Validation Cancel/staleness/failure now enter their terminal phase before cancelling old work. Retirement returns the exact identity reserved before that cancellation, and each continuation checks it before assigning feedback or scheduling work. Source capture builds a local Owner before installing it and rechecks the original request afterward. A cancellation or model-read callback may explicitly start another check or close the owner; an older start/terminal continuation cannot adopt that request's token, replace its snapshot/phase or report against it.

Update invalidation now detaches the old task, readiness/admission flags and mailbox before invoking cancellation. Cleanup cannot subsequently clear a newly admitted task or its receipt. Start captures applied endpoint/trust values locally and checks its reserved identity before installing them. Refresh also checks identity after reading applied preferences. Cancel/stale/failure phase changes precede cleanup and guard their feedback with the reserved identity. Failure entry points carry their original request token rather than invalidating first and accidentally adopting a cleanup-created successor. Failed checks retire their private request identity; successful reviewed results retain their existing identity policy.

Final close still marks CLOSED before retirement. A cancellation-created close cannot be relabelled CANCELLED/STALE/FAILED by its predecessor, and closed owners reject admission. A cancellation method's own Error retains its existing propagation policy, but its old task/flags have already detached and the terminal phase remains available for explicit retry. This does not add callback isolation, cancellation deadlines or a guarantee that a blocking cancellation returns. No automatic retry or additional user confirmation is introduced.

Nine new ManualCheckRetirementTest definitions cover validation and fake-update owners: cancellation-created ready successors; source-staleness successors; Future observation failure whose cancellation creates a successor without reading/reporting obsolete failure text; a start superseded during retirement; direct final closure and closure during cancellation; cancellation faults followed by explicit retry; executor-admission failure whose cleanup creates a ready successor; source-capture callbacks that start another check or close the owner; and applied-trust reads that replace or close an update request during refresh. A test override replaces only the task boundary after explicitly cancelling the original computation. Source-capture fixtures preserve authored values. Assertions retain exact private Properties/raw seed, committed project, revision/history/dirty state and preferences apart from deliberate caller changes used to trigger staleness.

All nine definitions and this implementation remain **uncompiled and untested**. Existing fourteen completion definitions/four earlier adaptations, thirteen validation and 59 update native receipt cases, saved-model comparisons and independent engine replay dimensions remain unchanged. Fake fetchers return local release metadata; no update network request, browser or clipboard action ran. No compiler, tests, probe, consumer, checker, packaging or workflow ran. Source review and git whitespace/content/metadata checks are the available verification; the observed 922-test/120-suite baseline and all acceptance gates do not advance.
