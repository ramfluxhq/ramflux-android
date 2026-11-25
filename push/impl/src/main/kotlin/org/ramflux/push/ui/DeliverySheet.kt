// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.push.impl.R
import org.ramflux.push.triggers.DeliveryTriggers

/** Tells the sheet that the permission screen was left, so it can decide whether to show (G1). */
@HiltViewModel
class DeliveryPromptViewModel @Inject constructor(private val triggers: DeliveryTriggers) : ViewModel() {
    fun permissionFlowFinished() = triggers.onPermissionFlowFinished()
}

@HiltViewModel
class DeliverySheetViewModel @Inject constructor(private val triggers: DeliveryTriggers) : ViewModel() {
    private val local = MutableStateFlow<List<Int>?>(null)
    val steps: StateFlow<List<Int>?> = local.asStateFlow()
    val requested: StateFlow<Boolean> = triggers.sheetRequest

    fun decide() {
        viewModelScope.launch { local.value = withContext(Dispatchers.Default) { triggers.sheetSteps() } }
    }

    fun close() {
        local.value = null
    }
}

/** A callback for the permission screen to call when it closes. */
@Composable
fun rememberPermissionFlowFinished(vm: DeliveryPromptViewModel = hiltViewModel()): () -> Unit = vm::permissionFlowFinished

/** Hosts the one-time sheet "Make sure messages arrive on this phone" (G1); [onSetUp] opens the delivery page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliverySheetHost(onSetUp: () -> Unit, vm: DeliverySheetViewModel = hiltViewModel(key = "delivery-sheet")) {
    val requested by vm.requested.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    LaunchedEffect(requested) { if (requested) vm.decide() }
    val shown = steps ?: return
    val theme = LocalRFTheme.current
    ModalBottomSheet(onDismissRequest = vm::close) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = theme.gap.dp).testTag("delivery-sheet"),
            verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)
        ) {
            Text(stringResource(R.string.delivery_sheet_title), color = theme.text)
            Text(stringResource(R.string.delivery_sheet_body), color = theme.text)
            for (title in shown) Text(stringResource(title), color = theme.text)
            KitButton(
                stringResource(R.string.delivery_sheet_set_up),
                {
                    vm.close()
                    onSetUp()
                },
                tag = "delivery-sheet-set-up"
            )
            KitButton(
                stringResource(R.string.delivery_sheet_not_now),
                vm::close,
                kind = KitButtonKind.Secondary,
                tag = "delivery-sheet-not-now"
            )
        }
    }
}
