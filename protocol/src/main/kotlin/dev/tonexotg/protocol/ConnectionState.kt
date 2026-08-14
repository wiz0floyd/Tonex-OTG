package dev.tonexotg.protocol

/**
 * The lifecycle of a connection to a ToneX One pedal, owned end-to-end by the connection state
 * machine (S9).
 *
 * ```
 * Idle ──send Hello──► Connecting ──► Hello ──recv type 0x02──► GetState
 *      ──send RequestState──► ──recv type 0x0306──► Ready
 * ```
 * (Verified against both upstream implementations; see S9 for the full sequence including
 * post-`Ready` preset-name harvesting.)
 *
 * [Connecting] is this project's addition, covering the gap between "a caller asked to connect"
 * and "the Hello message was actually sent" — upstream state machines conflate that with
 * [Idle]; splitting it out gives the UI (S18) something honest to show the instant a connection
 * attempt starts, rather than nothing changing until the first response arrives.
 *
 * Every non-terminal state can transition to [Error] — a malformed frame, a timeout, or an
 * out-of-order message at any stage surfaces here rather than leaving the machine stuck (FR11).
 * A transport detach at any stage is expected to return the machine cleanly to [Idle], not to
 * [Error] (S9) — [Error] is for protocol-level failures, not for "the user unplugged the cable."
 *
 * This is deliberately a [sealed interface] rather than a plain enum with a separate
 * `lastError: TonexError?` side property: an error and the state being errored can never go out
 * of sync with each other if they are the same value.
 */
sealed interface ConnectionState {

    /** No transport attached, or a previous session ended cleanly. The resting state. */
    data object Idle : ConnectionState

    /** A transport has been supplied and a connection attempt is in progress. */
    data object Connecting : ConnectionState

    /** The Hello message has been sent; awaiting the pedal's Hello response (wire type `0x02`). */
    data object Hello : ConnectionState

    /**
     * The Hello response was received; a state request has been sent and the machine is
     * awaiting the pedal's full state update (wire type `0x0306`).
     */
    data object GetState : ConnectionState

    /**
     * The handshake is complete. Preset names and master volume are harvested immediately
     * after entering this state (S9); parameter reads/writes and preset selection are only
     * valid once here.
     */
    data object Ready : ConnectionState

    /**
     * The connection failed and is not usable. [cause] is the specific, typed reason — never a
     * generic failure with no explanation (FR11).
     *
     * Recovering from this state requires a fresh connection attempt (a new [TonexTransport]
     * and a transition back through [Connecting]); there is no implicit retry.
     */
    data class Error(val cause: TonexError) : ConnectionState
}
