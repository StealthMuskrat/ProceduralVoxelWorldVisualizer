# Performance and capture numeric controls — unvalidated development

This connected source batch extends specification 4.1.2/4.3 numeric pairing through Performance settings and the Profiler's bounded next-capture limits. Existing private preference and capture/publication contracts remain authoritative. No compilation, JUnit, native replay, independent consumer, checker, schema generator or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain. Main is authoritative.

## Bounded exact integer pairs

ExactLongSlider now accepts a declared minimum, maximum and suggested radius. It intersects the nearby range with the field's existing domain using checked, bounded integer arithmetic. ExactLongInput sizes text for that domain, retains the original text-widget identity and uses a separate slider ID. Narrow controls stack. The original seed overload keeps its full signed-long domain and radius 256, so the seed/project controls retain their contract.

Small fields expose their complete domain: worker counts, preview dimensions, LOD/distance controls and all four capture limits. Memory, upload and resident-chunk ranges use matching bounded neighborhoods; the potentially very large preview-cell budget uses a nearby 1,048,576-cell radius. Text still accepts the complete existing domain, including budgets above double precision and Long.MAX_VALUE. Only the bounded offset uses native double storage; anchors and proposed values remain exact longs. Native temporary slider text is disabled. The adjacent text field supplies the complete live value without a rounded absolute slider label.

Out-of-range, malformed or displayed/accepted disagreement disables slider publication and preserves raw text. A disabled finite placeholder never becomes a setting. Unchanged rounded proposals do not normalize leading signs/zeros. Held ranges stay anchored through their own edits; changing a domain or radius during an interaction now blocks its remaining proposals as an ownership change. Ended/hidden/new-identity interactions can establish a fresh range. The original finite, ties-to-even, exact-addition and raw-replacement checks remain.

## Private Performance settings

Every Performance field now routes slider proposals through the same PerformanceEditSession.edit path as typing. Existing field bounds and coupled default-extent/maximum-dimension validation are unchanged. A valid field can be adjusted while another field is invalid; Apply remains blocked until the complete candidate is valid. Sliders never move another field automatically.

Stale active preferences disable the old draft's sliders while retaining text for review; Revert remains explicit. Apply Performance still publishes the complete validated preference candidate with its existing save/retry/merge behavior. Private input remains excluded from Save Preferences. Worker, engine-memory and default-extent restart notices still compare active values with the launched runtime; slider movement alone does not replace the engine, change project data or publish preferences. Revert/reload clears widget ownership along with the draft buffers.

## Profiler next-capture limits

Duration, maximum samples, maximum series and operation stride now use the same bounded pairs, with their original input IDs and complete displayed-field validation. Focus/recovery targets the exact text input after its slider. Edits configure only the next capture; current captures retain their immutable Options, and prepared exports retain their frozen samples. Invalid limits still prevent Start Capture through both the panel and the existing Debug entry point. Slider edits do not start/stop/clear instrumentation or consume an export review.

The central Performance and capture numeric limits Help guide and shared tooltip text describe domains, private values, coupled repair and explicit Apply/Start Capture. Project/model/preference/capture schemas, seed derivation, computational algorithms and engine/headless dependencies are unchanged. This remains a scoped control implementation, not certification of universal scalar presentation or the full numeric/metadata audit.

## Deferred definitions

Twelve BoundedIntegerInputTest definitions cover independent BigInteger range expectations for every Performance field, full capture domains, positive long budgets above 2^53, out-of-domain raw input without clamping, held policy changes, narrowed-domain correction, seed-overload compatibility, invalid/fixed policies, immutable previous capture options and displayed-field agreement, one-field coupled repair, private exact budgets with external active changes, and searchable guidance.

PerformancePreferencesProbe now defines a real slider edit around 9007199254740993, unchanged active preferences/file, disabled invalid-budget refusal and restoration before its existing complete Apply/save/retry replay. ProfileLayoutProbe defines a real next-duration edit preserving the previous capture and reviewed export, disabled invalid-stride refusal, and pair identities/ranges/geometry throughout its existing 18 section/layout receipts. Its future checker independently checks all four capture domains and distinct widget IDs. The existing independent preference/engine/profile replays retain their final expected data.

Existing manual-only workflow commands and artifact directories already include these probes/checkers, so no workflow change or dispatch was needed. Full native activation/reflow/focus/held-input cases, large-scale cost, physical keyboard/DPI/platform/Windows 11 and final GUI/release acceptance remain pending. Source definitions are not observed pass evidence.
