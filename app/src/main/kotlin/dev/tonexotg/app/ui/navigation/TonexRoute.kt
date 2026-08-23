package dev.tonexotg.app.ui.navigation

/**
 * Every destination in the app's single `NavHost` (S23, issue #74). See
 * `docs/architecture/s23-ui-wiring.md` for the route graph and why it is this small.
 *
 * **No route takes an argument, deliberately.** The Parameter Editor edits whatever preset the
 * pedal currently reports as active, read live from
 * [dev.tonexotg.protocol.TonexController.activePreset] — never a preset index captured at
 * navigation time. D3 §6.2 (`docs/design/interaction-spec.md`) requires exactly this: a
 * footswitch-driven preset change while the editor is open must swap the editor to the new
 * preset, because `ParameterWriteMessage`'s wire payload carries no preset index and therefore
 * always addresses whatever is active *now*. A `editor/{presetIndex}` route would encode a
 * preset identity the write path does not actually honour, and would make a stale-screen /
 * misdirected-write hazard representable in the navigation graph itself. Do not add one.
 */
enum class TonexRoute(val route: String) {

    /** Start destination: all 20 onboard presets (S16). */
    PRESET_LIST("presets"),

    /** Quick tier + full-tier accordion for the *currently active* preset (S17). */
    PARAMETER_EDITOR("editor"),

    /** Credits, licences, version info (S19). */
    ABOUT("about"),

    /** Diagnostics entry point + app preferences (issue #96). */
    SETTINGS("settings"),
}
