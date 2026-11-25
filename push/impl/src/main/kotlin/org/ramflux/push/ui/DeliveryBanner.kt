// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ramflux.designsystem.kit.KitSecurityWarning
import org.ramflux.designsystem.kit.KitWarningTone
import org.ramflux.push.impl.R
import org.ramflux.push.triggers.DeliveryProblem
import org.ramflux.push.triggers.DeliveryTriggers

/** Holds the problem the banner shows; it stays up while the problem lasts and is raised again only after its cap. */
@HiltViewModel
class DeliveryBannerViewModel @Inject constructor(private val triggers: DeliveryTriggers) : ViewModel() {
    private val local = MutableStateFlow<DeliveryProblem?>(null)
    val problem: StateFlow<DeliveryProblem?> = local.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val shown = local.value
            val present = withContext(Dispatchers.Default) { triggers.problems() }
            if (shown != null && shown in present) return@launch
            val next = withContext(Dispatchers.Default) { triggers.nextBanner(System.currentTimeMillis()) }
            if (next != null) triggers.markBannerShown(next, System.currentTimeMillis())
            local.value = next
        }
    }
}

/** The conversation-list banner for a hard delivery problem (G2) or a force stop (G4); [onOpen] opens the delivery page. */
@Composable
fun DeliveryBannerNavigation(onOpen: () -> Unit, vm: DeliveryBannerViewModel = hiltViewModel(key = "delivery-banner")) {
    val problem by vm.problem.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val shown = problem ?: return
    Column {
        KitSecurityWarning(stringResource(R.string.delivery_title), stringResource(bannerText(shown)), KitWarningTone.Warning)
        TextButton(onClick = onOpen) { Text(stringResource(R.string.delivery_banner_fix)) }
    }
}

private fun bannerText(problem: DeliveryProblem): Int = when (problem) {
    DeliveryProblem.MESSAGES_CHANNEL -> R.string.delivery_banner_messages
    DeliveryProblem.CALLS_CHANNEL -> R.string.delivery_banner_calls
    DeliveryProblem.FULL_SCREEN -> R.string.delivery_banner_full_screen
    DeliveryProblem.BACKGROUND -> R.string.delivery_banner_background
    DeliveryProblem.STANDBY -> R.string.delivery_banner_standby
    DeliveryProblem.PLAY_SERVICES -> R.string.delivery_banner_play
    DeliveryProblem.FORCE_STOP -> R.string.delivery_banner_force_stop
}
