// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.ramflux.android.R
import org.ramflux.data.Appearance
import org.ramflux.data.AppearanceRepository
import org.ramflux.data.LanguageRepository
import org.ramflux.designsystem.AppLanguageProvider
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.ThemeCatalog
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitConversationRow

/** How long a banner stays before it dismisses itself. */
internal const val BANNER_VISIBLE_MS = 4_000L

private val SWIPE_UP_DISMISS = 24.dp

/**
 * Adds the banner's own [ComposeView] to [activity]'s window, above the app content. The host view keeps the
 * banner out of autofill, content capture and (API 34+) of accessibility services that are not accessibility
 * tools; the banner text is announced through a polite live region instead of `announceForAccessibility`. With no
 * banner the view has no height, so it never intercepts touches.
 */
fun installInAppBannerHost(
    activity: ComponentActivity,
    banners: InAppBanners,
    language: LanguageRepository,
    appearance: AppearanceRepository
) {
    val host = ComposeView(activity).apply {
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val locale by language.language.collectAsStateWithLifecycle()
            val look by appearance.appearance.collectAsStateWithLifecycle()
            val dark = ThemeCatalog.find(look.themeId)?.dark ?: when (look.value) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.DARK -> true
                Appearance.LIGHT -> false
            }
            AppLanguageProvider(locale.value.tag) {
                RamfluxTheme(dark, themeId = look.themeId) { InAppBannerOverlay(banners) }
            }
        }
    }
    activity.addContentView(
        host,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
    )
}

/** The banner on screen, if any. Its state is plain composition state: nothing here is saved. */
@Composable
internal fun InAppBannerOverlay(banners: InAppBanners) {
    val banner by banners.current.collectAsStateWithLifecycle()
    val shown = banner ?: return
    LaunchedEffect(shown) {
        delay(BANNER_VISIBLE_MS)
        banners.dismiss(shown)
    }
    val theme = LocalRFTheme.current
    val threshold = with(LocalDensity.current) { SWIPE_UP_DISMISS.toPx() }
    val preview = when (val kind = shown.preview) {
        is BannerPreview.Text -> kind.line
        BannerPreview.Call -> stringResource(R.string.banner_preview_call)
        BannerPreview.Update -> stringResource(R.string.banner_preview_update)
        BannerPreview.Attachment -> stringResource(R.string.banner_preview_attachment)
        BannerPreview.Generic -> stringResource(R.string.notification_new_message)
    }
    val shape = RoundedCornerShape(theme.radius_card.dp)
    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(theme.surface_white)
            .pointerInput(shown) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled < -threshold) banners.dismiss(shown) },
                    onDragCancel = { travelled = 0f },
                    onVerticalDrag = { _, delta -> travelled += delta }
                )
            }
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    ) {
        KitConversationRow(
            title = shown.sender,
            preview = preview,
            time = "",
            avatar = { KitAvatar(shown.sender.take(1), BANNER_AVATAR, KitAvatarKind.Person) },
            onClick = { banners.open(shown) },
            tag = "in-app-banner"
        )
    }
}

private const val BANNER_AVATAR = 40f
