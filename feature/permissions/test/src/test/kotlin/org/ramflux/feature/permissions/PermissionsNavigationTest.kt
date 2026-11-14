// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.widget.Button
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.permissions.api.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private inner class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val intents = mutableListOf<Intent>()
        var code = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode
            intents.add(contract.createIntent(app, input))
        }
    }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
    private fun button(tag: String): Button = requireNotNull(ShadowDialog.getLatestDialog())
        .window?.decorView?.findViewWithTag<Button>(tag) ?: error("native action missing")

    @Test fun realNavigationDenyDismissesWithoutSystemIntent() {
        app.getSharedPreferences("os-permissions", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val registry = Registry()
        val vm = PermissionsViewModel(AndroidOsPermissionsRepository(app))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                AppLanguageProvider(
                    "zh-CN"
                ) { RamfluxTheme { PermissionsNavigation(PermissionsRoute(PermissionEntry.CAMERA_SCAN), {}, vm = vm) } }
            }
        }
        await { !vm.state.value.loading && vm.state.value.explanation != null }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(button("host-confirmation-deny").isFocused)
            button("host-confirmation-deny").performClick()
        }
        compose.waitForIdle()
        assertTrue(registry.intents.isEmpty())
        assertNull(vm.state.value.explanation)
    }

    @Test fun realNavigationUsesRequestContractThenRereadsGrantAndRevocation() {
        app.getSharedPreferences("os-permissions", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val registry = Registry()
        val vm = PermissionsViewModel(AndroidOsPermissionsRepository(app))
        var ready = 0
        var owner: LifecycleOwner? = null
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                owner = LocalLifecycleOwner.current
                AppLanguageProvider("en") {
                    RamfluxTheme { PermissionsNavigation(PermissionsRoute(PermissionEntry.CAMERA_SCAN), {}, { ready++ }, vm = vm) }
                }
            }
        }
        await { vm.state.value.explanation != null }
        compose.waitForIdle()
        compose.runOnIdle { button("host-confirmation-confirm").performClick() }
        await { registry.intents.isNotEmpty() || vm.state.value.failed }
        assertFalse("System failure: ${vm.state.value}", vm.state.value.failed)
        assertEquals("androidx.activity.result.contract.action.REQUEST_PERMISSIONS", registry.intents.single().action)
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            registry.dispatchResult(registry.code, true)
        }
        await { ready == 1 }
        compose.runOnIdle {
            shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
            val lifecycle = requireNotNull(owner).lifecycle as LifecycleRegistry
            lifecycle.currentState = Lifecycle.State.STARTED
            lifecycle.currentState = Lifecycle.State.RESUMED
        }
        await { vm.state.value.needed }
        assertEquals(1, registry.intents.size)
    }

    @Test fun postCreationNotificationUsesOnlyItsContractAndDenialDoesNotReprompt() {
        app.getSharedPreferences("os-permissions", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val registry = Registry()
        val vm = PermissionsViewModel(AndroidOsPermissionsRepository(app))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                AppLanguageProvider("en") {
                    RamfluxTheme {
                        PermissionsNavigation(PermissionsRoute(PermissionEntry.AFTER_ACCOUNT_CREATION, "created-account"), {}, vm = vm)
                    }
                }
            }
        }
        await { vm.state.value.explanation == OsPermission.NOTIFICATIONS }
        compose.waitForIdle()
        assertTrue(registry.intents.isEmpty())
        compose.runOnIdle { button("host-confirmation-confirm").performClick() }
        await { registry.intents.isNotEmpty() }
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            registry.intents.single().getStringArrayExtra("androidx.activity.result.contract.extra.PERMISSIONS")
        )
        compose.runOnIdle { registry.dispatchResult(registry.code, false) }
        await { !vm.state.value.waiting }
        assertNull(vm.state.value.explanation)
        assertEquals(1, registry.intents.size)
    }

    @Test fun batteryExplanationLaunchesOnlySystemSettingsFromItsRow() {
        val registry = Registry()
        val vm = PermissionsViewModel(AndroidOsPermissionsRepository(app))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                AppLanguageProvider("en") { RamfluxTheme { PermissionsNavigation(PermissionsRoute(), {}, vm = vm) } }
            }
        }
        await { !vm.state.value.loading }
        compose.waitForIdle()
        assertTrue(registry.intents.isEmpty())
        compose.onNodeWithTag("BATTERY").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(OsPermission.BATTERY, vm.state.value.explanation)
        assertTrue(registry.intents.isEmpty())
        compose.runOnIdle { button("host-confirmation-confirm").performClick() }
        await { registry.intents.isNotEmpty() }
        assertEquals(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, registry.intents.single().action)
    }

    @Test fun photoSelectionUsesSystemPickerAndNeverRequestsBroadAccessOrClaimsSend() {
        app.getSharedPreferences("os-permissions", 0).edit().clear().commit()
        val registry = Registry()
        val vm = PermissionsViewModel(AndroidOsPermissionsRepository(app))
        val selected = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                AppLanguageProvider("en") {
                    RamfluxTheme {
                        PermissionsNavigation(PermissionsRoute(PermissionEntry.ATTACHMENTS), {}, onSelection = selected::add, vm = vm)
                    }
                }
            }
        }
        await { !vm.state.value.loading }
        compose.waitForIdle()
        compose.onNodeWithTag("pick-photo").performScrollTo().performClick()
        await { registry.intents.isNotEmpty() }
        assertEquals("android.provider.action.PICK_IMAGES", registry.intents.single().action)
        compose.runOnIdle { registry.dispatchResult(registry.code, android.net.Uri.parse("content://picker/photo")) }
        await { vm.state.value.selected }
        compose.waitForIdle()
        compose.onNodeWithText("Selected on this device. No attachment has been sent.").assertExists()
        assertEquals(listOf("content://picker/photo"), selected)
        assertFalse(app.getSharedPreferences("os-permissions", 0).all.keys.any { it.startsWith("asked-") })
    }
}
