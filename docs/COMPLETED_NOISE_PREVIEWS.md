# Complete-worker gates for structural noise previews

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending.

## Readiness, worker completion and successful admission

NoiseEditSession already retained structural plans and captured planner failures until the current resolution/dialog controls permitted acceptance. A readiness receipt could still authorize adoption while posting remained unfinished or before executor.execute returned successfully. A later posting/admission failure could consequently arrive after the private plan had been accepted.

Receipt callbacks now only mark readiness. Explicit acceptance requires that receipt, normal complete worker return and successful executor admission before taking the mailbox. Inline acceptance inside posting or after inline task execution but before executor admission returns leaves the same request/version, previous feedback and all committed/private state intact. A later ordinary owner acceptance finishes the original action without an extra confirmation or automatic retry.

Captured planner failures wait for the same complete boundary. A poster/admission failure cannot hide behind an unaccepted successful plan or captured planner failure. Operational task-status/get faults retain their existing refresh recovery policy; this does not make every operational failure wait for Cancel.

## Original-request terminal reads and retirement

Refresh captures its original request before observing the Future. Acceptance rechecks that same identity after both refresh and the complete-worker check, before taking any payload. A terminal read that creates a ready replacement resolution and then returns normally or throws cannot consume or fail the replacement. The existing nonblocking completion helper contains status/get faults and preserves interruption from terminal reads.

Retirement returns the identity reserved before cancellation callbacks. Scheduling, plan installation, staleness and failure feedback check that exact identity. If cancelling an accepted plan's completed task creates a newer ready resolution, the old plan cannot overwrite it. Failure assigns its recovery phase before cleanup and checks ownership again after describing the Throwable, so a message getter can create a retry or close the dialog without obsolete feedback overwriting that outcome.

Cancel and consumed private staging already close the owner before task cancellation. A cancellation callback cannot admit another resolution into a closed owner. Opening project/lifetime/revision, Properties identity and complete value snapshots, selection/kind, raw seed version and current displayed-dialog checks retain their existing authority.

## Private preview, Stage and parent Apply remain distinct

The accepted preview only updates the dialog's private plan. Explicit resolved Stage applies that plan to the existing Properties draft, closes the dialog and leaves the committed project/history unchanged. Parent Properties Apply remains the separate document transaction with Undo/Redo; Save remains explicit. The original automatic-action policy and focus handoff remain unchanged.

Public synchronous paths, NoiseAuthoring rules, resource UUIDs, bindings, parameters, schemas, project/package formats and field/voxel semantics are unchanged. Engine/model/storage remain independent of the editor/native stack.

Twelve NoiseCompletionTest definitions cover held real posting and late faults; inline posting/admission and subsequent failure; captured planner feedback; completed work without a receipt; closure of held success/failure; terminal status/get/interrupted-read faults; terminal reads creating ready successors; reentrant failure descriptions; accepted-plan cancellation creating a successor; and final closure before cancellation-driven resolution changes. The successful lifecycle defines separate preview/Stage/parent Apply and one Undo/Redo transaction with independent (1+2)*4 = 12 arithmetic.

Real-worker fixtures use latches, release in finally blocks and explicit executor completion barriers. One existing BackgroundNoiseEditTest real-worker definition now waits for that complete boundary before acceptance. BackgroundNoiseEditProbe already waits through its queued task's executor barrier. No definition or probe ran; no new native case, workflow command or generated-world fixture is added. Existing native counts, complete saved-model/schema comparisons and independent mathematical replay dimensions remain unchanged.

Source review, git whitespace/content checks and repository metadata review are the available checks. Owner snapshot capture/comparison/projection/staging/focus costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
