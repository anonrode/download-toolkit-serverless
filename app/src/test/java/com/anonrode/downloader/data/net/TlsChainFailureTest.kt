package com.anonrode.downloader.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

/**
 * TLS chain-failure classification. Only chain/handshake failures justify the
 * trust-all retry (the wetafiles.com chain-break in v3.0.4, and the
 * StreamValidator probe that produces an IOException wrapping the SSL error);
 * timeouts, DNS and HTTP errors must stay real failures.
 */
class TlsChainFailureTest {

    @Test
    fun certPathFailureIsTls() {
        assertTrue(isTlsChainFailure(CertPathValidatorException("Trust anchor for certification path not found")))
    }

    @Test
    fun sslExceptionIsTls() {
        assertTrue(isTlsChainFailure(SSLHandshakeException("unexpected end of file")))
    }

    @Test
    fun messageMatchesWithoutTypedException() {
        // Surfaced as a plain IOException wrapping the SSL error on some
        // clients — the message walk finds it.
        assertTrue(isTlsChainFailure(IOException("javax.net.ssl.SSLPeerUnverifiedException: Trust anchor for certification path not found")))
    }

    @Test
    fun wrappedInIoExceptionStillTls() {
        assertTrue(isTlsChainFailure(IOException("probe failed", SSLHandshakeException("certificate_unknown"))))
    }

    @Test
    fun timeoutIsNotTls() {
        assertFalse(isTlsChainFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun dnsFailureIsNotTls() {
        assertFalse(isTlsChainFailure(UnknownHostException("no such host")))
    }

    @Test
    fun httpErrorIsNotTls() {
        assertFalse(isTlsChainFailure(IOException("HTTP 502 Bad Gateway")))
    }

    @Test
    fun nullOrUnrelatedMessageIsNotTls() {
        assertFalse(isTlsChainFailure(IOException()))
        assertFalse(isTlsChainFailure(IOException("clean")))
    }
}
