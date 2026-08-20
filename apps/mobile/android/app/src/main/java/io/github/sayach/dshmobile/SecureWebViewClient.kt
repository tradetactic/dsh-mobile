package io.github.sayach.dshmobile

import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Categories of main-frame failures that need a native recovery surface. */
internal enum class LoadFailure {
    TLS,
    NETWORK,
}

/** Enforces origin policy and accepts only the pairing-key-pinned private CA. */
internal class SecureWebViewClient(
    private val origin: GatewayOrigin,
    private val caCertificate: ByteArray,
    private val openExternal: (Uri) -> Unit,
    private val onBlocked: () -> Unit,
    private val onFailure: (LoadFailure) -> Unit,
    private val onLoaded: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val candidate = request.url.toString()
        if (GatewayUrlPolicy.isSameOrigin(origin, candidate)) return false
        if (request.hasGesture() && GatewayUrlPolicy.isExternalHttps(candidate)) {
            openExternal(request.url)
        } else {
            onBlocked()
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        if (url != "about:blank" && !GatewayUrlPolicy.isSameOrigin(origin, url)) {
            view.stopLoading()
            onBlocked()
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (GatewayUrlPolicy.isSameOrigin(origin, url)) onLoaded()
    }

    /**
     * SslCertificate.getX509Certificate() is API 29 and this app ships minSdk 26, so on
     * Android 8/9 it throws NoSuchMethodError inside the WebView callback and crashes the
     * app. Below API 29 the DER bytes are recovered from the saveState bundle instead.
     */
    private fun leafCertificate(certificate: SslCertificate): X509Certificate? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            certificate.x509Certificate
        } else {
            val der = SslCertificate.saveState(certificate).getByteArray("x509-certificate")
                ?: return@runCatching null
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
    }.getOrNull()

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val leaf = leafCertificate(error.certificate)
        val pinned = leaf != null
            && error.primaryError == SslError.SSL_UNTRUSTED
            && GatewayUrlPolicy.isSameOrigin(origin, error.url)
            && PinnedTls.acceptsWebViewLeaf(origin, caCertificate, leaf)
        if (pinned) {
            handler.proceed()
        } else {
            handler.cancel()
            view.stopLoading()
            onFailure(LoadFailure.TLS)
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onFailure(LoadFailure.NETWORK)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            onFailure(LoadFailure.NETWORK)
        }
    }

    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        callback.backToSafety(true)
        onFailure(LoadFailure.NETWORK)
    }
}
