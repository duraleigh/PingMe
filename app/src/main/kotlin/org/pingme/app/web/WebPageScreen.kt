// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.web

import android.content.Intent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.pingme.app.R
import org.pingme.app.login.browserUserAgent
import org.pingme.core.connector.CredentialStore
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.NetworkId
import org.pingme.core.store.AccountRepository
import javax.inject.Inject
import org.pingme.core.ui.R as UiR

/**
 * A page from a network, opened inside PingMe with the account's own sign-in, so a shared
 * Instagram post or reel shows here without the Instagram app or an outside browser
 * (owner, Gate G7). The page's cookies are the account's saved sign-in cookies.
 */
@Serializable
data class WebPageDest(
    val url: String,
    val accountId: String,
)

/** Which links a network's account can open signed in, by host. */
fun inAppHosts(network: NetworkId): List<String> =
    when (network) {
        NetworkId.INSTAGRAM -> listOf("instagram.com")
        NetworkId.MESSENGER -> listOf("facebook.com", "messenger.com", "fb.com")
        else -> emptyList()
    }

fun opensInApp(
    url: String,
    network: NetworkId,
): Boolean {
    val host = url.toUri().host?.lowercase() ?: return false
    return inAppHosts(network).any { host == it || host.endsWith(".$it") }
}

/**
 * How this chat opens links: the network's own pages inside PingMe with the account's
 * sign-in (owner, Gate G7), everything else in the phone's browser; null when the network
 * has no such pages, so the card uses the browser directly.
 */
fun linkOpenerFor(
    account: Account?,
    onOpenPage: ((String, AccountId) -> Unit)?,
    browser: (String) -> Unit,
): ((String) -> Unit)? {
    if (account == null || onOpenPage == null) return null
    if (inAppHosts(account.network).isEmpty()) return null
    return { url -> if (opensInApp(url, account.network)) onOpenPage(url, account.id) else browser(url) }
}

/** The signed-in page's cookies, and the page they belong to. */
data class WebPageState(
    val cookies: Map<String, String> = emptyMap(),
    val domains: List<String> = emptyList(),
    val ready: Boolean = false,
)

@HiltViewModel
class WebPageViewModel
    @Inject
    constructor(
        private val accounts: AccountRepository,
        private val credentials: CredentialStore,
    ) : ViewModel() {
        private val form = MutableStateFlow(WebPageState())
        val state: StateFlow<WebPageState> = form

        fun load(accountId: String) {
            if (form.value.ready) return
            viewModelScope.launch {
                val account = accounts.get(AccountId(accountId))
                val secret = account?.let { credentials.load(it.credentialRef) }
                val cookies =
                    secret
                        ?.let { bytes ->
                            runCatching {
                                Json.decodeFromString(
                                    MapSerializer(String.serializer(), String.serializer()),
                                    bytes.decodeToString(),
                                )
                            }.getOrNull()
                        }.orEmpty()
                form.value = WebPageState(cookies, account?.let(::inAppHosts).orEmpty().map { ".$it" }, ready = true)
            }
        }

        private fun inAppHosts(account: Account) = inAppHosts(account.network)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPageRoute(
    url: String,
    accountId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebPageViewModel = hiltViewModel(),
) {
    viewModel.load(accountId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        modifier,
        topBar = {
            TopAppBar(
                title = { Text(url.toUri().host.orEmpty()) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.web_back))
                    }
                },
                actions = {
                    IconButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) {
                        Icon(painterResource(UiR.drawable.ic_open_in_new), stringResource(R.string.web_open_in_browser))
                    }
                },
            )
        },
    ) { padding ->
        if (state.ready) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        val jar = CookieManager.getInstance()
                        jar.setAcceptThirdPartyCookies(this, true)
                        state.domains.forEach { domain ->
                            state.cookies.forEach { (name, value) ->
                                jar.setCookie(
                                    "https://${domain.trimStart('.')}",
                                    "$name=$value; Domain=$domain; Path=/; Secure",
                                )
                            }
                        }
                        jar.flush()
                        webViewClient = WebViewClient()
                        @Suppress("SetJavaScriptEnabled") // The networks' pages need it.
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = browserUserAgent(settings.userAgentString)
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
