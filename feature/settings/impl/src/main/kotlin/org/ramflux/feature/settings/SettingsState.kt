// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.AppLanguage
import org.ramflux.data.ContactRequestPolicy
import org.ramflux.designsystem.*

enum class SettingsPage { GENERAL, ACCOUNT, PRIVACY, APP_LOCK, DEVICES, PAIRING, APPEARANCE, LANGUAGE, DIAGNOSTICS, USERNAME }

/** Where the username claim form stands: closed, the name step, or the recovery-phrase step. */
enum class SettingsUsernameStep { CLOSED, NAME, PHRASE }

/** The claim or change form. The recovery phrase is never held here: the view model takes it as typed. */
@Immutable data class SettingsUsernameClaim(
    val step: SettingsUsernameStep = SettingsUsernameStep.CLOSED,
    val name: String = "",
    val working: Boolean = false,
    val problem: UiText? = null,
    val outcome: UiText? = null
)

/** Where linking a new device stands on this phone (the inviting side). */
enum class PairingStep { IDLE, STARTING, INVITED, COMPARE, REVIEW, WORKING, ENDED }

/** How a pairing ended; [FAILED] carries the SDK's result code in [SettingsPairing.result]. */
enum class PairingEnd { COMPLETED, CANCELLED, EXPIRED, REJECTED, FAILED }

/**
 * Display state of a pairing. The invitation token is not here: it stays in erasable characters in the
 * view model and is drawn through [org.ramflux.designsystem.kit.KitQrCode] or
 * [org.ramflux.designsystem.kit.LtrSecretWords] only.
 */
@Immutable data class SettingsPairing(
    val step: PairingStep = PairingStep.IDLE,
    val expiresAt: Long? = null,
    /** Number of drawn invitation chunks; zero once the joining device has used it. */
    val invitationChunks: Int = 0,
    /** QR modules per side of the invitation; zero when there is none or it does not fit a QR code. */
    val invitationModules: Int = 0,
    /** The invitation is shown as numbered text chunks instead of the QR code. */
    val invitationAsText: Boolean = false,
    val emoji: ImmutableList<Int> = persistentListOf(),
    val peerName: String? = null,
    val peerDevice: String? = null,
    val capabilities: ImmutableList<String> = persistentListOf(),
    val leaseExpiresAt: Long? = null,
    /** The approval waits for the host confirmation. */
    val confirmApprove: Boolean = false,
    val ended: PairingEnd? = null,
    val result: String? = null,
    val problem: UiText? = null
)

/** The contact request policy; [open] and [username] are the beta's open-account rows, blocked by the SDK. */
@Immutable data class SettingsPrivacy(
    val policy: ContactRequestPolicy? = null,
    val loading: Boolean = false,
    val problem: UiText? = null
)

/**
 * The app lock of the active account. [enabled] and [timeoutMs] are what the SDK last reported; [unavailable] disables
 * the page when the SDK cannot enforce the lock on this device, [problem] shows a refused change inline.
 */
@Immutable data class SettingsAppLock(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val enabled: Boolean = false,
    val timeoutMs: Long = APP_LOCK_TIMEOUTS.first(),
    val unavailable: UiText? = null,
    val problem: UiText? = null
)

/** The timeouts the app lock page offers, in milliseconds: zero is "immediately". */
val APP_LOCK_TIMEOUTS: List<Long> = listOf(0L, 60_000L, 300_000L, 900_000L, 3_600_000L)

/** The account-code change form; digits stay in the view model, the state only counts them. */
@Immutable data class SettingsCodeChange(
    val open: Boolean = false,
    val currentDigits: Int = 0,
    val nextDigits: Int = 0,
    val working: Boolean = false,
    val done: Boolean = false,
    val problem: UiText? = null
)

@Immutable data class SettingsThemeChoice(val id: String, val label: UiText)

@Immutable data class SettingsDevice(val id: String, val local: Boolean)

@Immutable data class SettingsStatus(val sessions: UiText, val sync: UiText, val node: UiText, val lastRun: UiText)

@Immutable data class SettingsState(
    val page: SettingsPage = SettingsPage.GENERAL,
    val accountLabel: String? = null,
    /** This account's username, or why the directory cannot say (`username.status`); null until read. */
    val username: UiText? = null,
    val usernameClaim: SettingsUsernameClaim = SettingsUsernameClaim(),
    val accountRequired: Boolean = false,
    val loading: Boolean = false,
    val selectedTheme: String? = null,
    val themes: ImmutableList<SettingsThemeChoice> = ThemeCatalog.themes.map {
        SettingsThemeChoice(it.id, uiText(it.nameRes))
    }.toPersistentList(),
    val savingTheme: Boolean = false,
    /** Follow-system first, then every language the app has resources for. */
    val languages: ImmutableList<AppLanguage> = persistentListOf(AppLanguage.SYSTEM),
    val selectedLanguage: AppLanguage = AppLanguage.SYSTEM,
    val savedLanguage: AppLanguage = AppLanguage.SYSTEM,
    val languageDraft: AppLanguage? = null,
    val savingLanguage: Boolean = false,
    val devices: ImmutableList<SettingsDevice> = persistentListOf(),
    val devicesLoaded: Boolean = false,
    val deviceProblem: UiText? = null,
    /** The device whose revocation waits for confirmation. */
    val confirmRevoke: String? = null,
    /** Sign-out or removal of the active account, waiting for confirmation. */
    val confirmExit: SettingsAccountExit? = null,
    val status: SettingsStatus? = null,
    val pairing: SettingsPairing = SettingsPairing(),
    val privacy: SettingsPrivacy = SettingsPrivacy(),
    val appLock: SettingsAppLock = SettingsAppLock(),
    val codeChange: SettingsCodeChange = SettingsCodeChange(),
    val statusProblem: UiText? = null,
    val problem: UiText? = null,
    val unavailable: UiText? = null
)

/** How the active account leaves this phone: signed out (its data stays, the code opens it again) or removed with its data. */
enum class SettingsAccountExit { SIGN_OUT, REMOVE }
