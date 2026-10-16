package com.anonrode.downloader.data.rules

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Step-7 signing-rotation verifier for the OTA rules envelope (design doc
 * `scratch/ota_rule_engine_design.md` §3.4/§7; the model is Firefox Remote
 * Settings §1.4: a pinned ROOT set lives in the app, a LEAF signing key is
 * delivered INSIDE the envelope and is trusted only once the pinned root's
 * signature over it checks out — key rotation without an app release).
 *
 * Envelope v2 stays ADDITIVE-ONLY. Legacy mode (exactly as shipped before
 * this file existed):
 *
 *     {"v":2,"alg":"aes-128-cbc","iv":"..","payload":"<b64 ct>",
 *      "sig":"<b64 ECDSA-P256-SHA256 by a pinned ROOT over the payload string>"}
 *
 * Chain mode (new, optional object; its presence selects chain mode):
 *
 *     {"v":2,"alg":"aes-128-cbc","iv":"..","payload":"<b64 ct>",
 *      "sig":"<b64 ECDSA-P256-SHA256 by the LEAF over the payload string>",
 *      "chain":{"leafPubB64":"<leaf SPKI-DER base64>",
 *               "rootSig":"<b64 ECDSA-P256-SHA256, by a pinned ROOT, over
 *                          the CANONICAL bytes of leafPubB64 = the exact
 *                          ASCII bytes of that base64 string>",
 *               "alg":"P256-SHA256"}}
 *
 * Failure semantics (contract of this verifier):
 *  - Chain mode either accepts a COMPLETE chain (root-pin -> leaf -> payload
 *    sig) or fails. A failing/malformed chain NEVER falls back to legacy
 *    verification and never accepts an unsigned or root-less payload.
 *  - Unknown chain algorithm => fail closed.
 *  - Dual-pin overlap: every function receives the full pinned ROOT list and
 *    adopts the "verify first that succeeds" rule — a payload under ANY
 *    pinned root (root-1 legacy or root-2 rotation) is accepted.
 *
 * Everything here is PURE (no Android deps): java.security + java.util.Base64
 * only, so it runs on the JVM and in unit tests identically to on-device.
 */
internal object SignatureChain {

    /** The one chain algorithm this build understands. Anything else in
     *  chain.alg is a fail-closed rejection. */
    const val CHAIN_ALG: String = "P256-SHA256"

    /**
     * LEGACY mode: [sigB64] is an ECDSA-P256-SHA256 signature made by one of
     * the pinned root keys themselves, over the ASCII [payloadB64] string.
     * "Verify first that succeeds" over [rootPinsB64] — this is how the
     * dual-pin rotation window accepts both the old and the new key on
     * legacy-shaped envelopes. False when no pin verifies.
     */
    fun verifyLegacyMode(payloadB64: String, sigB64: String, rootPinsB64: List<String>): Boolean {
        if (payloadB64.isBlank() || sigB64.isBlank() || rootPinsB64.isEmpty()) return false
        for (pin in rootPinsB64) {
            if (pin.isBlank()) continue
            if (verifyEcdsaP256Sha256(pin, payloadB64, sigB64)) return true
        }
        return false
    }

    /**
     * CHAIN mode: the envelope delivered a leaf public key plus a root
     * signature vouching for it.
     *  1. [chainAlg] must equal [CHAIN_ALG] — unknown = reject.
     *  2. [rootSigB64] must verify against SOME pinned root over the
     *     canonical bytes of the leaf (the ASCII [leafPubB64] string as it
     *     will be re-verified byte-for-byte on every device).
     *  3. [sigB64] must verify against the DELIVERED leaf key over
     *     [payloadB64].
     * A payload signed directly by a root (and not by the enveloped leaf)
     * does NOT pass chain mode — the envelope is a chain or it is rejected.
     */
    fun verifyChainMode(
        payloadB64: String,
        sigB64: String,
        leafPubB64: String,
        rootSigB64: String,
        chainAlg: String?,
        rootPinsB64: List<String>
    ): Boolean {
        if (payloadB64.isBlank() || sigB64.isBlank() ||
            leafPubB64.isBlank() || rootSigB64.isBlank()
        ) return false
        if (chainAlg != CHAIN_ALG) return false
        if (rootPinsB64.isEmpty() || rootPinsB64.all { it.isBlank() }) return false
        // Step 1 — the leaf is trusted only under a pinned root's signature
        // over its canonical bytes (Remote Settings pinned-root model §1.4);
        // first pin that verifies wins, none => reject.
        val rootVouched = rootPinsB64.any { pin ->
            pin.isNotBlank() && verifyEcdsaP256Sha256(pin, leafPubB64, rootSigB64)
        }
        if (!rootVouched) return false
        // Step 2 — the payload signature must come from the DELIVERED leaf.
        return verifyEcdsaP256Sha256(leafPubB64, payloadB64, sigB64)
    }

    /**
     * Single low-level primitive: ECDSA-P256-SHA256 over the US-ASCII bytes
     * of [data], verified with the X509/SPKI-DER base64 public key [pubB64].
     * Every failure mode (bad base64, bad key encoding, bad DER signature,
     * mismatch) collapses to false — verifiers never throw outward.
     */
    fun verifyEcdsaP256Sha256(pubB64: String, data: String, sigB64: String): Boolean {
        return try {
            val pub = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pubB64))
            )
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(data.toByteArray(Charsets.US_ASCII))
            s.verify(java.util.Base64.getDecoder().decode(sigB64))
        } catch (_: Exception) {
            false
        }
    }
}
