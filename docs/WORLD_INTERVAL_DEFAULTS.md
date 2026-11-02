# World interval defaults — unvalidated development

This main-branch follow-up addresses specification 10.2.8 and 10.4.8–10.4.10: atomic interval-list inheritance, exact endpoint behavior and rejection of overlap. It corrects Add Type Interval defaults after row reordering and at floating-point extremes. Source implementation and deferred definitions are not acceptance evidence. No compilation, JUnit, engine replay, native probe, checker or workflow ran; the owner testing pause and 922-test / 120-suite validated baseline remain.

## An explicit valid proposal

Add Type Interval previously started at the last displayed row's maximum and added 1. Reordering could therefore propose an overlap. At large finite bounds, adding 1 could fail to advance the bound and produce an invalid or useless interval.

WorldIntervalDefaults now reads a bounded sorted copy of the existing ranges; it does not reorder or repair the authored list. With no ranges it proposes the existing [0,1) default. Otherwise it prefers a range above the greatest endpoint, preserving that endpoint's ownership. It uses the next representable finite value when ordinary unit arithmetic cannot advance. Adjacent endpoints include an available endpoint so the proposal can select at least one representable field value.

When the upper finite domain is exhausted, an unused upper endpoint, the first internal gap, a lower prefix or an unused lower endpoint can supply the proposal. Gaps with no representable value are skipped. Full finite coverage disables Add. Existing malformed, non-finite, empty or overlapping ranges also disable Add with repair guidance; no old value is changed. The existing 256-row cap remains. A missing Voxel Type disables Add with Voxel Manager guidance; the feature creates no resources implicitly.

The panel shows the exact proposed endpoints/inclusion and first existing Voxel Type before Add. These endpoint strings use round-trip precision so adjacent large bounds cannot appear identical through a compact display. The proposal is recomputed after the completed row loop and any delivered structural edit. Add's native identity includes the proposed range and type UUID, preventing an already-held gesture from publishing a replacement proposal after a value-only change. Successful Add still appends one private row, retires structural controls and uses the existing one-transaction Properties Apply. Sparse placement overrides, Reset, explicit Save and unrelated invalid gate seed inputs retain their established behavior.

## Signed-zero endpoint validation

Interval membership already treats -0.0 and +0.0 as numerically equal. The former comparison order distinguished their sign bits and could reject a valid zero singleton beside an interval open at zero. WorldSemantics.compareTypeIntervals now supplies the same numerical endpoint order to validation and proposal sorting. Minima are compared first, then maxima; equal numerical zeros tie without rewriting either value. Closed overlapping zeros are still rejected. Authored row order, diagnostic indices, UUID references, serialized signed-zero bits, file format/schema and runtime membership semantics are unchanged. This corrects acceptance of an existing valid boundary case; it is not a document migration.

## Deferred definitions

Nine WorldIntervalDefaultsTest definitions cover first-type eligibility, default unit ranges, reordering invariance and ownership, endpoint inclusivity, arithmetic saturation, both finite-domain endpoints, internal/prefix gaps, actual representable emptiness, invalid/capped inputs and signed-zero ordering. Candidate ranges are checked through model validation without mutating the existing ranges. Two TypeIntervalOrderingTest definitions specify valid open-zero/singleton combinations and closed-zero overlap rejection for both authored orders and both zero signs, retaining the full candidate JSON.

Two additional WorldListEngineTest definitions specify seven authored states at roots 2/4, over X=-4..10 and Y/Z=-1..1: 1,890 new exact voxel and 1,890 primary-field trace expectations. Reordered interval defaults, endpoint ownership, an atomic typeIntervals-only override and Reset have independent half-X mathematical expectations. Positive/negative zero fields distinguish the zero singleton's type from the adjacent open interval's type through the engine.

The existing WorldListProbe now adds actual interval reorder/Add and held Add replacement phases after its nine synthetic layouts. Independent complete private/project JSON expectations allow only the already specified driver copy, interval reorder and new [1,2) row. The held phase injects a private endpoint change between native press/release, verifies a new Add identity and requires no extra row. The probe retains invalid gate seed blocking, one Apply/Undo/Redo, saved-state and preference checks. Its existing manual-only Linux/Windows commands/artifacts remain wired; no workflow change or dispatch was needed. Apply is the existing real state transaction, not a native footer-button click claim.

All definitions remain uncompiled/untested. Native missing-type/full-domain/limit/extreme-input cases, every proposal/held-selector transition, physical/DPI/Windows 11/full GUI and final release gates remain pending. Main remains authoritative and no branch was added.
