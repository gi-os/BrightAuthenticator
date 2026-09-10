package com.gios.lightauth

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.lightauth.data.StoredAccount
import com.gios.lightauth.hw.LightKey
import com.gios.lightauth.hw.LightKeys
import com.gios.lightauth.hw.LocalWheelBus
import com.gios.lightauth.hw.WheelBus
import com.gios.lightauth.hw.WheelScroll
import com.gios.lightauth.time.TimeSource
import com.gios.lightauth.totp.TotpGenerator
import com.gios.lightauth.ui.ActionBar
import com.gios.lightauth.ui.AuthViewModel
import com.gios.lightauth.ui.BarAction
import com.gios.lightauth.ui.EmptyState
import com.gios.lightauth.ui.LockScreen
import com.gios.lightauth.ui.MenuRow
import com.gios.lightauth.ui.barColors
import com.gios.lightauth.ui.theme.LightAuthTheme
import com.gios.lightauth.vault.Vault
import com.gios.lightauth.vault.VaultState
import kotlinx.coroutines.launch

/**
 * Another app asks for one code.
 *
 * Web Tools sits on a sign-in page that wants the six digits; instead of reading them off this
 * screen and typing them into that one, it starts this activity for a result with
 * [ACTION_PICK_CODE] and the site's host in [EXTRA_SITE]. The vault's rules hold: a PIN, if
 * set, is asked here first, and nothing leaves until a row is tapped. What leaves is the code
 * and the account's name, once, in the result. No secret ever crosses. Accounts whose issuer or
 * label mention the site come first; the rest follow, because issuers are spelled every way.
 */
class PickCodeActivity : ComponentActivity() {

    private val wheel = WheelBus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> { if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1); return true }
            LightKey.WheelDown -> { if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1); return true }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimeSource.init(this)
        Vault.init(this)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val site = intent.getStringExtra(EXTRA_SITE).orEmpty().lowercase()
        setContent {
            LightAuthTheme {
                val vm: AuthViewModel = viewModel()
                val vaultState by Vault.state.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    if (vaultState == VaultState.Locked) {
                        LockScreen(onUnlocked = { pin -> vm.onVaultOpened(pin) })
                    } else {
                        Picker(vm, site)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Picker(vm: AuthViewModel, site: String) {
        val all by vm.accounts.collectAsStateWithLifecycle()
        val accounts = rank(all, site)
        val listState = rememberLazyListState()
        WheelScroll(listState)
        Scaffold(
            containerColor = Color.Black,
            topBar = { TopAppBar(colors = barColors(), title = { Text(if (site.isBlank()) "A code for…" else "A code for $site") }) },
            bottomBar = { ActionBar(listOf(BarAction("CANCEL") { setResult(RESULT_CANCELED); finish() })) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
                if (accounts.isEmpty()) {
                    EmptyState("no accounts added\n\nAdd one in Authenticator first.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(accounts, key = { it.id }) { account ->
                            MenuRow(
                                label = account.issuer.ifBlank { "Unknown" },
                                sub = "(${account.label.ifBlank { "—" }})",
                                onClick = { hand(vm, account) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun hand(vm: AuthViewModel, account: StoredAccount) {
        lifecycleScope.launch {
            val secret = vm.loadSecret(account.id)
            if (secret == null) { setResult(RESULT_CANCELED); finish(); return@launch }
            val code = TotpGenerator.generate(
                secret, account.digits, account.period, account.algorithm,
                currentUnixTime = TimeSource.nowSeconds(),
            )
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_CODE, code.code)
                    .putExtra(EXTRA_ACCOUNT, account.issuer.ifBlank { account.label })
                    .putExtra(EXTRA_SECONDS_LEFT, code.remainingSeconds),
            )
            finish()
        }
    }

    companion object {
        const val ACTION_PICK_CODE = "com.gios.lightauth.PICK_CODE"
        const val EXTRA_SITE = "site"
        const val EXTRA_CODE = "code"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_SECONDS_LEFT = "secondsLeft"

        /**
         * Accounts that mention the site first. "github.com" matches an issuer "GitHub" and a
         * label "gio@github.com"; the match is on the registrable name without its ending, so
         * "accounts.google.com" still finds "Google".
         */
        fun rank(accounts: List<StoredAccount>, site: String): List<StoredAccount> {
            val key = siteKey(site)
            if (key.isEmpty()) return accounts
            return accounts.sortedByDescending { a ->
                val hay = (a.issuer + " " + a.label).lowercase()
                if (hay.contains(key)) 1 else 0
            }
        }

        /** "accounts.google.com" → "google"; "www.github.com" → "github"; "" → "". */
        fun siteKey(host: String): String {
            val parts = host.lowercase().split('.').filter { it.isNotBlank() }
            if (parts.isEmpty()) return ""
            if (parts.size == 1) return parts[0]
            // Two-part public suffixes (co.uk, com.au): take the label before them.
            val secondLevel = setOf("co", "com", "org", "net", "ac", "gov", "edu")
            val i = if (parts.size >= 3 && parts[parts.size - 2] in secondLevel && parts.last().length == 2) parts.size - 3 else parts.size - 2
            return parts.getOrElse(i) { parts[0] }
        }
    }
}
