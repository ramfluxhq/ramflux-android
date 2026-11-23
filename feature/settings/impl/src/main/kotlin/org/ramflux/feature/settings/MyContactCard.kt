// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ContactDirectoryRepository
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitQrCode
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.LtrCode
import org.ramflux.feature.settings.impl.R

/**
 * This account's own contact card. [text] is the `ramflux-card:v3:` form a peer pastes; [qrModules] is non-zero
 * when the `ramflux://contact/v3/` link fits a QR code. Public values; the card names the home node so a peer
 * that adds the phone by it pins that node.
 */
@Immutable data class MyCardState(
    val loading: Boolean = true,
    val text: String? = null,
    val qrModules: Int = 0,
    val failed: Boolean = false
)

@HiltViewModel
class MyContactCardViewModel @Inject constructor(
    private val accounts: AccountAccessRepository,
    private val contacts: ContactDirectoryRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(MyCardState())
    val state: StateFlow<MyCardState> = local.asStateFlow()
    private var qr: InvitationQr? = null
    private var job: Job? = null

    fun load() {
        job?.cancel()
        local.value = MyCardState()
        val generation = session.status.value.generation
        job = viewModelScope.launch {
            val active = (accounts.list() as? ClientResult.Ready)?.value?.let { catalog ->
                catalog.accounts.firstOrNull { it.id == catalog.activeId && it.unlocked }
            }
            val card = active?.let { contacts.ownCard(it.id) }
            if (session.status.value.phase != ClientPhase.OPEN || session.status.value.generation != generation) return@launch
            val ready = (card as? ClientResult.Ready)?.value
            if (ready == null) {
                local.value = MyCardState(loading = false, failed = true)
                return@launch
            }
            qr?.close()
            qr = InvitationQr.encode(ready.link.toCharArray())
            local.value = MyCardState(loading = false, text = ready.text, qrModules = qr?.modules ?: 0)
        }
    }

    fun lendQr(block: ((Int, Int) -> Boolean) -> Unit) {
        qr?.lend(block)
    }

    fun close() {
        job?.cancel()
        qr?.close()
        qr = null
        local.value = MyCardState()
    }

    override fun onCleared() {
        qr?.close()
        super.onCleared()
    }
}

@Composable
internal fun MyContactCard(
    state: MyCardState,
    lendQr: (((Int, Int) -> Boolean) -> Unit) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val theme = LocalRFTheme.current
    val context = LocalContext.current
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(theme.bg).testTag("my-card-page")) {
        KitPageHeader(stringResource(R.string.settings_my_card), onBack = onClose, tag = "my-card-header")
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(theme.gap.dp)) {
            KitMeta(stringResource(R.string.settings_my_card_explanation), Modifier.fillMaxWidth())
            when {
                state.loading -> KitMeta(
                    stringResource(R.string.settings_my_card_loading),
                    Modifier.fillMaxWidth().padding(top = theme.gap.dp)
                )
                state.failed -> {
                    KitTipBar(stringResource(R.string.settings_my_card_failed), "circle-alert", Modifier.padding(vertical = theme.gap.dp))
                    KitButton(
                        stringResource(R.string.settings_my_card_retry),
                        onRetry,
                        kind = KitButtonKind.Secondary,
                        tag = "my-card-retry"
                    )
                }
                state.text != null -> {
                    if (state.qrModules > 0) {
                        KitQrCode(
                            state.qrModules,
                            lendQr,
                            stringResource(R.string.settings_my_card_qr),
                            Modifier.padding(vertical = theme.gap.dp),
                            tag = "my-card-qr"
                        )
                    } else {
                        KitMeta(
                            stringResource(R.string.settings_my_card_too_long),
                            Modifier.fillMaxWidth().padding(vertical = theme.gap.dp)
                        )
                    }
                    LtrCode(state.text, Modifier.fillMaxWidth(), groupSize = CARD_GROUP, tag = "my-card-text")
                    KitButton(
                        stringResource(R.string.settings_my_card_share),
                        {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.text)
                            context.startActivity(Intent.createChooser(send, null))
                        },
                        Modifier.fillMaxWidth().padding(top = theme.gap.dp),
                        tag = "my-card-share"
                    )
                }
            }
        }
    }
}

private const val CARD_GROUP = 8
