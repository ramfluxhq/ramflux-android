// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.selftest

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.push.impl.R

/**
 * What the test ring's full-screen intent opens. It is the only screen that may show over the lock screen, so it holds
 * nothing but the test line: no account, conversation or contact data.
 */
class RingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            RamfluxTheme {
                Column(
                    Modifier.fillMaxSize().padding(dimensionResource(R.dimen.delivery_ring_padding)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.delivery_selftest_call_title))
                    Text(stringResource(R.string.delivery_selftest_text))
                    KitButton(stringResource(R.string.delivery_selftest_close), ::finishRing)
                }
            }
        }
    }

    private fun finishRing() {
        getSystemService(NotificationManager::class.java).cancel(LocalSelfTest.CALL_ID)
        finish()
    }
}
