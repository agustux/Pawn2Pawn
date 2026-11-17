package com.agustux.pawn2pawn.net

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** [app] = the device advertises Pawn2Pawn, so [name] is the player's chosen name rather than the system device name. */
data class Peer(val name: String, val address: String, val app: Boolean = false)

sealed interface LinkState {
    data object Idle : LinkState
    data object Searching : LinkState
    data class Connecting(val peerName: String) : LinkState
    /** Both players must see the same [code] and accept; [accepted] = this player has. */
    data class Verify(val code: String, val accepted: Boolean = false) : LinkState
    data class Connected(val isHost: Boolean) : LinkState
    data class Failed(val reason: String) : LinkState
}

/** An encrypted, authenticated text channel to one nearby phone (see [secureSession]). */
interface Link {
    val state: StateFlow<LinkState>
    val peers: StateFlow<List<Peer>>
    val incoming: SharedFlow<String>
    /** This phone's name as other phones' system UI shows it. */
    val systemName: StateFlow<String>
    fun start()
    fun stop()
    fun setName(name: String)
    fun connect(peer: Peer)
    /** The player confirmed the Verify code matches the other phone's. To reject, stop() or start(). */
    fun accept()
    fun send(line: String)
}
