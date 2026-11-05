# Exact Inspector ticket and Future observation

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. A29/A32/A33, native/Windows 11 and final release acceptance do not advance.

## A terminal read belongs to one exact attachment

InspectorTask production acceptance already required a shown Ticket, complete worker return and current Inspector controls. It rechecked the Ticket after terminal observation and the current-input callback, but did not retain the identity of the Future it had observed. Replacing the Future under the same Ticket during either callback could let the older call consume a newer payload or turn the callback's exception into a failure against the replacement. Completion/failure advisory polls also lacked an original-ticket/Future check.

Acceptance now captures its original Future beside the shown Ticket. Both must still be current after terminal observation and after the current-input callback, including its throwing path, before any mailbox take or failure consumption. Advisory completed/failure polling uses the same exact pair. A terminal status/get implementation that attaches another Future, creates another Ticket or closes the task cannot return the obsolete result as the replacement's completion/failure. Reusing the same Request object for a later begin does not reconstruct the old Ticket's authority.

This does not make advisory polling publish a result. A normally completed worker still needs its owned readiness receipt for production acceptance; a terminal Future fault retains the existing receipt-independent recovery path. Reattachment retains the Ticket's existing CPU mailbox ownership, but an older observation cannot authorize the replacement Future. The replacement needs its own complete observation and ordinary acceptance.

## Successful legacy finish requires complete normal work

The low-level finishSuccessful helper previously treated failure() returning null as success, including unfinished or unattached work. It now requires normal complete return from the exact captured Future, retaining the exact Ticket throughout observation. It cannot retire unfinished work or an attachment/ticket created while observing its predecessor. A completed normal legacy callback remains single-use and detaches its Future without interruption.

The explicit finish(owner) operation retains its existing owned-retirement behavior, including callers intentionally retiring a task without an attached worker. Production trace/pick/miss/failure publication continues through accept with shown controls and mailbox authority; no new dialog action or confirmation is added.

## Cancellation callbacks cannot revive an older begin

Retirement now returns the generation reserved before cancellation callbacks. Begin checks that reservation before installing its new intent. If cancelling the predecessor creates a successor and that successor is immediately consumed or cancelled, its now-empty ticket slot does not let the older begin reopen itself. The existing protections for an actively pending successor and closed owner remain.

Cancel and close retain their prior ordering; late worker publication and delivery remain unable to refill retired slots. Acceptance still consumes before presentation/log/native consequences and does not interrupt a successfully completed Future. Inspector source/project/document/coordinate/view checks, Escape/Cancel/collapse/close behavior, exact retry targets and accepted trace/log semantics remain unchanged. Project/private/raw/history/preferences, public model/API/schema/project/package formats and independent field/voxel mathematics remain unchanged. Engine/model/storage remain independent of editor/native dependencies.

## Deferred definitions and available review

Fourteen InspectorObservationOwnershipTest definitions cover a real prepared/delivered trace held before worker return, a late real posting failure, all advisory/legacy observers leaving unfinished work unread, same-ticket pending and complete Future replacements, current callbacks that replace an attachment and return true/false/throw, status/get reentrancy across exact Futures and Tickets, reused Request identity, closure during terminal reads, complete normal legacy single-use behavior, missing worker versus explicit retirement, current terminal fault/interruption preservation, cancellation-created successors consumed/cancelled before an outer begin resumes, and cancellation/closure of held real payloads before late failure/delivery.

Real-worker fixtures use latches, release in finally blocks and explicit executor completion barriers. Synthetic immutable trace/pick fixtures reuse the existing ready-Inspector constructors; no engine compilation or new arithmetic evaluator is introduced. All definitions remain uncompiled/untested. Existing native cases/receipts, ten-log analytic replay, complete saved-model/schema comparisons, manual-only workflow wiring and independent mathematical replay dimensions remain unchanged. No native case or generated-world fixture is added and no definition or probe ran.

Source review, git whitespace/content checks and repository metadata review are the available checks. Owner source capture/comparison/presentation/log/native costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
