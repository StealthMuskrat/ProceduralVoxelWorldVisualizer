# Complete-worker gates for direct and guarded draft Apply

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, network update check or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**; no acceptance gate advances.

## A receipt cannot commit a private draft

PropertiesApplySession, SearchNavigation and ResourceSelectionSession already deferred publication until current footer/modal controls accepted their delivered result. Their callbacks still discarded the task handle, however. A queued receipt could be processed while its posting worker was still returning, and explicit inline acceptance could commit before the handoff or executor call later failed. The missing task also hid a later posting failure from owner observation.

Delivery now only marks readiness. Each owner retains the FutureTask and requires a delivered receipt, normal complete worker return and successful return from executor.execute before taking the CPU mailbox. Guarded owners additionally retain their existing admission-in-progress fence. Acceptance attempted inside either unfinished boundary leaves the same work version, project, private draft, selection, history and previous diagnostics intact. A later owning observation completes the original explicit Apply automatically; there is no additional confirmation or automatic retry.

| Owner | Publication after the complete gate |
| --- | --- |
| Properties Apply | One guarded resource transaction followed by the ordinary private draft refresh. |
| Source/Find/reference navigation | One guarded resource transaction, then separately checked navigation and reveal. |
| Resource browser selection | One guarded resource transaction, then re-resolved sibling selection and installation. |

A handoff failure after a receipt and an executor failure after inline work preserve the existing document/private state and expose the existing bounded recovery feedback. A failed admission takes precedence over a worker value, rejection or captured failure that could not yet be accepted. Captured compiler failures and rejected-candidate diagnostics wait for the same complete gate. Operational task faults retain the existing owner refresh recovery policy; this follow-up does not claim all operational faults defer to Cancel.

Cancel, Escape, close, collapse/suspension, stale source/input/feedback and explicit retry retain their current separate panel policies. Keeping the task handle allows retirement to cancel/remove unfinished work after a receipt. Late callbacks retain identities only and cannot repopulate a retired mailbox. Final Properties closure is visible before task cancellation can attempt another request; guarded owners retain their existing disposed-before-cleanup order.

## Original-request ownership during observation and feedback

Acceptance and refresh capture their original request identity before calling BackgroundFailure.completed. After a terminal Future read, the owner checks that original identity before consuming the mailbox or reporting its fault. A custom terminal observation that creates a fully ready successor cannot accept its value or attribute an old failure to it. The existing helper contains status/get Throwable and preserves interrupted terminal reads without blocking on unfinished work.

Failure handling also captures the retired identity before reading Throwable.getMessage. A message getter that creates a ready successor cannot overwrite its progress or report the obsolete failure against it. Current owner/source/private/raw/diagnostic/checkpoint/history/policy/destination gates remain authoritative around the final transaction. Accepted publication still consumes before ordinary completion callbacks, preserving one Undo boundary and explicit retry after preparation failure.

## Deferred definitions and unchanged contracts

Sixteen DraftApplyCompletionTest definitions exercise all three paths:

- A real worker held after posting its receipt, with normal completion and one Undo/Redo transaction only after release.
- A late posting failure with retained domain state and explicit retry; cancellation of success, rejection and captured failure before a late poster fault; closure during a held handoff.
- Acceptance inside inline posting and after the task returns but before executor submission returns; later poster and executor faults; captured failure/rejection feedback remaining private.
- Complete work without a delivered receipt; contained Future status/get faults and preserved interruption.
- Terminal reads creating ready successors before normal return or an exception; failure-message getters creating ready successors.
- Replacement while the predecessor handoff remains blocked; newer diagnostic feedback during that handoff; close visible before cancellation callbacks attempt resurrection.

Real-worker fixtures use latches and explicit executor barriers, with release in finally blocks; no fixed frame delay is used. Three existing real-worker definitions in PropertiesApplySessionTest, BackgroundNavigationTest and BackgroundBrowserSelectionTest now explicitly await an executor barrier before final acceptance. None of the new or adapted definitions ran.

Existing native controls, receipt counts, saved-model/schema comparisons and independent mathematical replay dimensions remain unchanged. No generated-world fixture, native case, workflow trigger or dispatch is added. This batch changes no headless/model/storage/public API, saved format, algorithm, field or voxel rule. Public synchronous navigation/selection constructors retain their existing behavior. Project Settings, file-transition draft Apply and other worker owners remain separate follow-up audit scope; this is not a repository-wide completion claim.

Source review, git whitespace/content checks and repository metadata review are the available checks. UI owner capture/comparison/history/refresh/rendering costs, broader feature/completion audit, measured native/Windows 11 and final release acceptance remain outstanding.
