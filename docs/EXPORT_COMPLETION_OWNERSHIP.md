# Configuration and export completion — unvalidated development

This source follow-up supports the file-publication and state-separation contracts in specification 2.2.4/2.6.4, 3.4.4 and 19.4. No compilation, JUnit, independent consumer, native probe, checker or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain. Main is authoritative.

## Terminal publication

PreferenceFiles, ResourcePackageFiles and ProfileIO previously attempted to delete their temporary pathname even after atomic replacement succeeded. A later cleanup failure could escape and cause the caller to report a failed operation despite the complete new destination already being published. A new file created at that old temporary pathname could also be deleted after the writer's ownership had ended. ProjectIO already ends temporary cleanup at successful publication; these paths now follow that boundary too. ProjectIO also now preserves primary failures across unchecked cleanup faults, matching the other writers.

Each writer records successful replacement and performs no later file operation. The corresponding existing caller can then publish its normal saved checkpoint, consume its reviewed export token or report export completion. The temporary pathname no longer belongs to this operation after replacement. Writers still stage complete content in a unique sibling, force it, and require atomic replacement without a non-atomic fallback. Resource-package project-path guards and validated snapshots, configuration size bounds, and frozen profile snapshots retain their contracts.

Before successful replacement, each writer attempts cleanup exactly once. If staging, a path guard, forcing or replacement has already failed, an IOException, RuntimeException or Error from cleanup is attached as a suppressed exception to that original failure. Reusing the same throwable for both phases keeps the original object without attempting self-suppression. Thus a secondary cleanup fault cannot replace the failure that caused rejection. A failed cleanup may leave its temporary file; this is not a claim of guaranteed recovery from exhausted VM resources. Fault definitions use finite injected exceptions and AssertionErrors.

The Storage/Replacement cleanup methods used for fault injection remain package-private. ProfileIO's Replacement stays a functional interface, so existing lambda callers remain compatible. Public signatures, JSON/CSV schemas, model values, private Apply transactions, dirty state, captures and engine arithmetic are unchanged. This does not broaden filesystem atomicity/durability guarantees or undo a provider operation that replaces a file and then throws instead of reporting success.

## Deferred definitions

Eleven additional definitions extend PreferenceFilesTest, ResourcePackageFilesTest, ProfileIOTest and ProjectSaveRecoveryTest:

- Complete publication returns normally despite a cleanup hook that would throw, retains the complete destination, and leaves a newly created file at the old temporary pathname untouched. Configuration reload checks the exact chosen width; package reload matches the owned resource JSON and leaves the source unchanged; both profile formats retain the reviewed capture ID.
- Rejected writes/replacements preserve the old destination and original IOException while attaching checked, runtime and Error cleanup failures. Configuration/package cases cover both staging and replacement boundaries; JSON and CSV each cover replacement. Cleanup is attempted once and the injected handler removes its owned temporary content before reporting its fault.
- The same IOException, RuntimeException or Error raised by both original work and cleanup remains the caught object with no self-suppression failure. Destination bytes remain intact; JSON and CSV are both included.

Two ProjectSaveRecoveryTest definitions additionally cover six original/unchecked-cleanup fault combinations and three same-throwable combinations, preserving the previous destination and exact original failure.

These are source definitions, not observed results. Existing ordinary/private preference-save decisions, resource export review/retry, frozen profiler export review and independent-client replay definitions remain deferred. No workflow changes or dispatch were needed. Full native save/retry, filesystem/provider, platform/Windows 11 and final acceptance/release gates remain pending.
