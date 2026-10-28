# Numeric editor display precision

Status: **implemented, awaiting validation** on `codex/development-phase`. The owner has deferred GitHub testing until development is complete. This change has not been compiled or tested.

The shared `UiFields.number` control now uses `%.17g` rather than five fixed fractional digits. This provides up to seventeen significant decimal digits for a double, permits scientific notation for small/large magnitudes and preserves signed zero in the formatted value. The former display could show a nonzero value such as 1e-9 as 0.00000, or hide the distinction between adjacent representable values near one. Re-entering a rounded display could then change the authored number.

The control remains an ImGui double input with the existing setter and parent Apply transaction. No float conversion, clamping, rounding step, seed conversion, model/schema change or evaluation change is introduced. Existing finite/range validation remains authoritative. This is a display-format correction; it does not add provisional invalid-text retention to every numeric input or change the separately owned signed-long seed controls.

The shared control is used beyond Properties, so some numeric controls may now show longer or scientific values. Native editing/focus behavior, exponent/subnormal and signed-zero round trips, narrow layouts/DPI, all callers and saved-project replay remain pending. No test definition or passing count is added for this localized reversible change. Existing native editing and engine replay workflows remain deferred with the rest of development validation.

[Application creation-default inputs](CREATION_INPUT_OWNERSHIP.md) subsequently replace that panel's separate five-decimal input path with retained raw fields and complete displayed-value checks. Its implementation and deferred precision/recovery definitions also remain uncompiled/untested.
