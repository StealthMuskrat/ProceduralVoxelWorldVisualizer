# Scene cancellation ownership — unvalidated

W06-C02 covers scene cancellation/retirement in the CPU-to-render handoff (1.4.1–1.4.3 and 14.1.3/14.1.5, ownership only). ChunkScene previously notified a token before releasing its entry's active slot and CPU reservation. A Source may register cancellation listeners: a listener that reentered close could settle that same entry again, while a throwing listener could skip settlement and the captured Future's cancellation. Live entry iteration and unconditional eviction accounting could also use state replaced during a callback.

Cancellation now captures the token/Future, revokes the job ID and finishes owner settlement before notifying either. Both notifications are attempted independently; a fault in one does not skip the other. During open-scene cancellation the first notification fault uses existing bounded failure feedback only if its retired entry/job remains current. Further notification faults do not mutate the original Throwables. Closed-scene teardown contains notification faults without reading their text and continues through other jobs and source closure. Source.close's own existing exception policy is unchanged.

Submission-failure paths also finish ownership before token notification, and terminal-Future fault paths attempt both notifications while retaining the observed primary failure for feedback. Queue rejection keeps its existing policy and rechecks current idle ownership before changing feedback. No old cancellation may mark a successor failed or release its reservation.

Planning, eviction and close traverse detached entry snapshots around host callbacks. Tick stops after planning/eviction if reentrant closure occurred. Eviction rechecks entry/job identity, idle state and current window/required membership before removing data or adjusting CPU counters. Optional-capacity reclamation counts only actual removals. This protects work admitted or made required by reentrant callbacks; it does not add general parallel access to UI-owned scene methods.

Callbacks remain cooperative and may block their notifying thread. Existing source/executor ownership, close-aware mailbox, immutable completed data, normal-Future/prepared-result acceptance, job/epoch checks, admission budgets, LOD/mesh algorithms and GPU upload ownership retain their contracts. Extra entry snapshots and guard work have no measured allocation/latency result. No public shape, headless dependency, numerical operation, schema, registry, authored format or archive layout changes.

## Deferred regression definitions

Five added ChunkSceneCompletionTest definitions specify:

- Three voxel/point/surface cases where a token listener closes the scene during camera-window cancellation, without double settlement or invalid traversal.
- Two active jobs whose token listeners and Future cancellation both throw unreadable Errors; both notification pairs and once-only source close must occur, with zero host text reads or Throwable mutation and zero closed ownership.
- Six data/mesh terminal-fault cases preserving the primary failure while cleanup notifications also fail; owner slots/reservations must be detached before either callback.
- Six inline data/mesh admission-fault cases where source cancellation observes settled ownership and the primary admission failure retains priority.
- One Future-cancellation callback admitting a same-entry successor, whose task, reservation and eventual healthy data must survive old cancellation/planning/eviction.

The controlled executor explicitly exposes cancellation callback/fault behavior; its own teardown disables injected faults. Existing real-worker, exact geometry, old-display, mailbox and native cases keep their separate scope. All 21 ChunkSceneCompletionTest definitions and the unchanged mailbox suite remain deferred at this revision. No new native probe, engine-JAR renderer consumer or generated artifact is added.

## Status

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git review adds no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Execute scene/mailbox/cancellation/mesh/LOD suites and native camera/closure/replacement cases after validation resumes. Wider W05/W06 queue/caller, resource/performance and hardware/release work remain open; local-main publication remains pending.
