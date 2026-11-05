# Complete-worker gates for Project Settings and file operations

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, network update check or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**; native/Windows 11 and final release acceptance remain pending.

## Readiness, completed posting and successful admission

ProjectSettingsSession and the ProjectFileWorkflow Draft Apply/Open/Save owners already waited for current panel/modal controls before publishing delivered values. Settings and transition Apply still discarded their task handles on delivery. Open and Save retained theirs but allowed explicit acceptance before worker posting or executor submission returned. A late handoff/executor failure could therefore follow a committed transaction, document replacement or file/checkpoint publication.

All four owners now retain task observation and require the request's readiness receipt, normal complete worker return and successful return from executor.execute before taking its mailbox. Delivery only marks readiness. Acceptance inside inline posting or after inline task return but before executor admission returns cannot publish. Current controls still accept the original explicit action automatically on a later owning observation; no additional confirmation or automatic retry is introduced.

| Owner | Publication protected by the complete gate |
| --- | --- |
| Project Settings | One project seed/inclusive-Y-bound transaction; separate Properties identity, values and raw input remain private. |
| Transition Apply Draft | One owned Properties transaction before continuing to the committed Save/Discard guard. |
| Project Open | Prepared document/lifetime/checkpoint installation before replacement notification and UI refresh. |
| Project Save | Atomic destination replacement and checkpoint installation before any pending New/Open/Exit continuation. |

Captured compiler/validation/preparation failures and rejected settings/draft candidates wait for that complete gate. Failed executor admission takes precedence over a captured value/failure that was never eligible for acceptance. Operational failed/cancelled Future recovery retains the existing owner refresh policy; this is not a claim that every operational fault waits for Cancel.

Current Cancel/Escape/close, suspension and stale-source policies remain independent of result completion. Exact displayed settings/path mismatch still retires a ready request while its worker is returning from posting. Complete-worker checks do not defer those input decisions. Save retirement invalidates and closes its owned staging payload, preserving prior destination bytes and checkpoint metadata; the finishing retired worker cannot reinstall its slot. A failed transition Save retains only standalone explicit Save retry, with no inherited New/Open/Exit permission.

## Original-request feedback and display ownership

Settings acceptance captures its original token before refresh and keeps the exact immutable raw map through displayed-map comparison, terminal observation and mailbox take. A displayed map whose getter creates a ready successor cannot cause the predecessor to consume that successor's result. Settings refresh likewise attributes terminal status/get faults only to its original request. Interrupted terminal reads preserve interruption through the existing BackgroundFailure helper.

Settings failure feedback checks the retired identity after reading Throwable.getMessage, and final Cancel marks the form closed before task cancellation. File preparation failures now use a separate current-request reporting path: a message getter that creates a ready successor or closes the workflow cannot report the obsolete preparation against it. Reporting for already consumed publication/refresh callbacks keeps its previous behavior, including recording a failed completion callback when a newer action exists. Exact source/private/raw/diagnostic/checkpoint/history/policy and current-opening gates remain authoritative around publication.

## Deferred definitions and remaining scope

Twenty-one ProjectOperationCompletionTest definitions cover all four owners and their distinct contracts:

- Real-worker posting held after its readiness receipt, normal completion and single settings/draft Undo/Redo publication; late posting failure, retained source/file state and explicit retry.
- Cancellation of success, rejection and fatal payloads before a late poster fault; final closure; acceptance inside both inline posting and executor admission; rejected executor admission and captured failure precedence.
- Captured failure feedback awaiting the complete gate; complete work without a delivered receipt; contained status/get faults and preserved interruption.
- Terminal observation and failure-message getters creating ready successors; final close before cancellation callbacks; failure-message getters closing the owner without retired primary feedback.
- Save before every New/Open/Exit intent, with successful continuation only after complete handoff and failure preserving destination/checkpoint state and deleting staging.
- Reentrant displayed-settings maps, independent Properties raw edits during settings handoff, mismatched displayed fields/paths during unfinished posting and newer committed values before acceptance.

Real-worker fixtures use latches and explicit executor barriers, with release in finally blocks. Two prior real-worker definitions in BackgroundProjectSettingsTest and BackgroundTransitionDraftTest now await an executor barrier before final acceptance. Existing Open thread fixtures already join their worker; native probes use completed-worker barriers or wait for terminal owner phases. None of these definitions or probes ran.

Existing native controls, receipt counts, complete saved-model/schema comparisons, independent field/voxel replay dimensions and workflow wiring remain unchanged. No new native case or generated-world fixture is added. Engine/model/storage dependencies, public synchronous paths, project/schema/API shapes, atomic persistence rules and authored mathematics are unchanged.

Source review, git whitespace/content checks and repository metadata review are the available checks. Owner capture/comparison/history/rename/cleanup/presentation costs, remaining background-operation and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up completes the identified settings/file-operation gate changes; it does not certify repository-wide completion.
