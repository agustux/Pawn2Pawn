package com.agustux.pawn2pawn.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated, encrypted session over any transport. The transports themselves are unauthenticated.
 *
 * Handshake, host H and client C, ECDH P-256. H commits to its key first, so someone relaying in the
 * middle must fix its keys before seeing ours and can't search for keys that make the codes match:
 *   H → C  SHA-256(pkH)
 *   C → H  pkC
 *   H → C  pkH   (C checks it against the commitment)
 * Both screens show a 6-digit code from (pkH, pkC); if the players see the same code, nobody is in
 * the middle (else 1 in a million). Then every frame is AES-256-GCM with a key per direction and a
 * counter nonce, so tampering, replay and reordering all fail. Each side's first frame is "ok":
 * its player accepted the code. [Link]'s state goes Verify → Connected once both have.
 */
internal suspend fun CoroutineScope.secureSession(
    input: InputStream,
    output: OutputStream,
    isHost: Boolean,
    out: Channel<String>,
    incoming: MutableSharedFlow<String>,
    state: MutableStateFlow<LinkState>,
    accepted: Deferred<Unit>,
    close: () -> Unit,
) {
    val din = DataInputStream(input)
    val dout = DataOutputStream(output.buffered())
    val watchdog = launch {
        delay(10_000) // a peer that stalls the handshake can't hold the link
        runCatching(close)
    }
    val (tx, rx, code) = try {
        handshake(din, dout, isHost)
    } finally {
        watchdog.cancel()
    }
    state.value = LinkState.Verify(code)
    launch {
        try {
            accepted.await()
            tx.write(dout, OK)
            for (l in out) tx.write(dout, l)
        } catch (e: IOException) {
            runCatching(close) // makes the reader below fail the link
        }
    }
    if (rx.read(din) != OK) throw IOException("bad confirmation")
    accepted.await()
    state.value = LinkState.Connected(isHost)
    while (true) incoming.tryEmit(rx.read(din))
}

private fun handshake(din: DataInputStream, dout: DataOutputStream, isHost: Boolean): Triple<Sealer, Sealer, String> {
    try {
        val me = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val mine = me.public.encoded
        val theirs: ByteArray
        if (isHost) {
            send(dout, sha256(mine))
            theirs = receive(din)
            send(dout, mine)
        } else {
            val commit = receive(din)
            send(dout, mine)
            theirs = receive(din)
            if (!MessageDigest.isEqual(sha256(theirs), commit)) throw IOException("commitment mismatch")
        }
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs)) as? ECPublicKey
        if (peer == null || peer.params.order != (me.public as ECPublicKey).params.order) throw IOException("wrong key type")
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(me.private)
            doPhase(peer, true)
            generateSecret()
        }
        val (h, c) = if (isHost) mine to theirs else theirs to mine
        val toClient = Sealer(sha256("h2c".toByteArray(), secret, h, c))
        val toHost = Sealer(sha256("c2h".toByteArray(), secret, h, c))
        val sas = (ByteBuffer.wrap(sha256("sas".toByteArray(), h, c)).int.toLong() and 0xffffffffL) % 1_000_000
        val code = "%06d".format(sas).chunked(3).joinToString(" ")
        return if (isHost) Triple(toClient, toHost, code) else Triple(toHost, toClient, code)
    } catch (e: GeneralSecurityException) {
        throw IOException(e)
    }
}

/** AES-256-GCM for one direction; the nonce is a message counter both sides track. */
private class Sealer(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    private fun next(mode: Int) =
        cipher.init(mode, key, GCMParameterSpec(128, ByteBuffer.allocate(12).putLong(4, counter++).array()))

    fun write(dout: DataOutputStream, line: String) = try {
        next(Cipher.ENCRYPT_MODE)
        send(dout, cipher.doFinal(line.toByteArray(Charsets.UTF_8)))
    } catch (e: GeneralSecurityException) {
        throw IOException(e)
    }

    fun read(din: DataInputStream): String = try {
        val frame = receive(din)
        next(Cipher.DECRYPT_MODE)
        String(cipher.doFinal(frame), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        throw IOException(e)
    }
}

private fun send(dout: DataOutputStream, b: ByteArray) {
    dout.writeInt(b.size)
    dout.write(b)
    dout.flush()
}

/** Bounded, so a peer can't exhaust memory with a huge length. */
private fun receive(din: DataInputStream): ByteArray {
    val n = din.readInt()
    if (n !in 0..MAX_FRAME) throw IOException("frame too large")
    return ByteArray(n).also(din::readFully)
}

private fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        parts.forEach(::update)
        digest()
    }

private const val OK = "ok"
private const val MAX_FRAME = 64 * 1024
