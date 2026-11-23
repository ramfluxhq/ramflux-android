// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.ramflux.data.AppLanguage
import org.ramflux.data.ContactRequestPolicy
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.settings.impl.R

/** A titled group of rows on one card, as the settings board (MOB-G02) groups them. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalRFTheme.current
    Column(verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
        Text(
            title,
            color = theme.secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = theme.gap.dp)
        )
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(theme.surface_white), content = content)
    }
}

internal fun LazyListScope.generalPage(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    // The board leaves a band of air under the header before the first group.
    item("top") { Spacer(Modifier.height(LocalRFTheme.current.gap.dp)) }
    item("group-account") {
        SettingsGroup(stringResource(R.string.settings_group_account)) {
            KitRow(
                stringResource(R.string.settings_account_security),
                { onAction(SettingsAction.Page(SettingsPage.ACCOUNT)) },
                icon = "user-round",
                value = state.accountLabel ?: stringResource(R.string.settings_account_needed_short),
                enabled = state.accountLabel != null,
                testTag = "account-security"
            )
            KitRow(
                stringResource(R.string.settings_devices_sync),
                { onAction(SettingsAction.Page(SettingsPage.DEVICES)) },
                icon = "smartphone",
                value = stringResource(R.string.settings_devices_sync_help),
                divider = false,
                testTag = "devices"
            )
        }
    }
    item("group-data") {
        SettingsGroup(stringResource(R.string.settings_group_data)) {
            KitRow(
                stringResource(R.string.settings_nodes),
                { onAction(SettingsAction.Connections) },
                icon = "network",
                value = stringResource(R.string.settings_manage_connections),
                testTag = "connections"
            )
            KitRow(
                stringResource(R.string.settings_backup_restore),
                { onAction(SettingsAction.Backup) },
                icon = "database-backup",
                value = stringResource(R.string.settings_backup_restore_help),
                testTag = "backup"
            )
            KitRow(
                stringResource(R.string.settings_storage),
                { onAction(SettingsAction.Storage) },
                icon = "hard-drive",
                value = stringResource(R.string.settings_storage_help),
                enabled = state.accountLabel != null,
                divider = false,
                testTag = "storage"
            )
        }
    }
    item("group-app") {
        SettingsGroup(stringResource(R.string.settings_group_app)) {
            KitRow(
                stringResource(R.string.settings_appearance_language),
                { onAction(SettingsAction.Page(SettingsPage.APPEARANCE)) },
                icon = "palette",
                value = stringResource(R.string.settings_appearance_language_help),
                testTag = "appearance"
            )
            KitRow(
                stringResource(R.string.settings_diagnostics_about),
                { onAction(SettingsAction.Page(SettingsPage.DIAGNOSTICS)) },
                icon = "activity",
                value = stringResource(R.string.settings_diagnostics_about_help),
                testTag = "diagnostics"
            )
            KitRow(
                stringResource(R.string.settings_permissions),
                { onAction(SettingsAction.Permissions) },
                icon = "sliders-horizontal",
                divider = false,
                testTag = "permissions"
            )
        }
    }
}

internal fun LazyListScope.accountPage(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    item("account-rows") {
        SettingsGroup(stringResource(R.string.settings_security)) {
            KitRow(
                stringResource(R.string.settings_change_code),
                { onAction(SettingsAction.OpenCodeChange) },
                icon = "lock-keyhole",
                value = stringResource(R.string.settings_change_code_help),
                enabled = state.accountLabel != null && !state.codeChange.working,
                testTag = "change-code"
            )
            KitRow(
                stringResource(R.string.settings_my_card),
                { onAction(SettingsAction.MyCard) },
                icon = "qr-code",
                value = stringResource(R.string.settings_my_card_help),
                enabled = state.accountLabel != null,
                testTag = "my-card"
            )
            KitRow(
                stringResource(R.string.settings_privacy),
                { onAction(SettingsAction.Page(SettingsPage.PRIVACY)) },
                icon = "shield",
                value = stringResource(R.string.settings_privacy_help),
                testTag = "privacy"
            )
            KitRow(
                stringResource(R.string.settings_lock_account),
                { onAction(SettingsAction.Lock) },
                icon = "lock",
                value = stringResource(R.string.settings_lock_account_help),
                enabled = state.accountLabel != null && !state.loading,
                divider = false,
                testTag = "lock-account"
            )
        }
    }
    item("account-exit") {
        SettingsGroup(stringResource(R.string.settings_account_exit)) {
            KitRow(
                stringResource(R.string.settings_sign_out),
                { onAction(SettingsAction.AccountExit(SettingsAccountExit.SIGN_OUT)) },
                icon = "user-minus",
                value = stringResource(R.string.settings_sign_out_help),
                enabled = state.accountLabel != null && !state.loading,
                testTag = "sign-out"
            )
            KitRow(
                stringResource(R.string.settings_remove_account),
                { onAction(SettingsAction.AccountExit(SettingsAccountExit.REMOVE)) },
                icon = "trash-2",
                value = stringResource(R.string.settings_remove_account_help),
                enabled = state.accountLabel != null && !state.loading,
                divider = false,
                testTag = "remove-account"
            )
        }
    }
    state.confirmExit?.let { exit ->
        val signOut = exit == SettingsAccountExit.SIGN_OUT
        item("confirm-exit") {
            Column(verticalArrangement = Arrangement.spacedBy(LocalRFTheme.current.gap.dp)) {
                KitSecurityWarning(
                    stringResource(
                        if (signOut) R.string.settings_sign_out_confirm_title else R.string.settings_remove_account_confirm_title
                    ),
                    stringResource(
                        if (signOut) R.string.settings_sign_out_confirm_body else R.string.settings_remove_account_confirm_body
                    ),
                    if (signOut) KitWarningTone.Warning else KitWarningTone.Danger
                )
                KitButton(
                    stringResource(if (signOut) R.string.settings_sign_out else R.string.settings_remove_account),
                    { onAction(SettingsAction.ConfirmAccountExit) },
                    kind = KitButtonKind.Destructive,
                    tag = "confirm-exit"
                )
                KitButton(
                    stringResource(R.string.settings_cancel),
                    { onAction(SettingsAction.CancelAccountExit) },
                    kind = KitButtonKind.Secondary,
                    tag = "cancel-exit"
                )
            }
        }
    }
    if (state.codeChange.done) {
        item(
            "code-changed"
        ) { Text(stringResource(R.string.settings_code_changed), Modifier.testTag("code-changed")) }
    }
    if (state.codeChange.open || state.codeChange.working) item("code-form") { CodeChangeForm(state.codeChange, onAction) }
}

@Composable
private fun CodeChangeForm(form: SettingsCodeChange, onAction: (SettingsAction) -> Unit) {
    val theme = LocalRFTheme.current
    val next = form.currentDigits == 6
    Column(Modifier.testTag("code-form"), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
        KitCodeField(stringResource(R.string.settings_code_current), form.currentDigits, focused = !next, tag = "code-current")
        KitCodeField(
            stringResource(R.string.settings_code_new),
            form.nextDigits,
            focused = next,
            error = form.problem?.render(),
            helper = stringResource(R.string.settings_code_new_help),
            tag = "code-new"
        )
        // Digits go straight to the view model's erasable arrays: no text editor, IME or undo history holds them.
        for (row in KEYPAD) {
            Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                for (key in row) {
                    Box(Modifier.weight(1f)) {
                        when (key) {
                            KEY_NONE -> Unit
                            KEY_DELETE -> KitButton(
                                stringResource(R.string.settings_code_delete),
                                { onAction(SettingsAction.CodeDigit(next, null)) },
                                kind = KitButtonKind.Secondary,
                                enabled = !form.working,
                                tag = "code-delete"
                            )
                            else -> KitButton(
                                key.toString(),
                                { onAction(SettingsAction.CodeDigit(next, '0' + key)) },
                                kind = KitButtonKind.Secondary,
                                enabled = !form.working,
                                tag = "code-key-$key"
                            )
                        }
                    }
                }
            }
        }
        KitButton(
            stringResource(R.string.settings_code_submit),
            { onAction(SettingsAction.SubmitCodeChange) },
            enabled = !form.working && form.currentDigits == 6 && form.nextDigits == 6,
            tag = "code-submit"
        )
        KitButton(stringResource(R.string.settings_cancel), {
            onAction(SettingsAction.CloseCodeChange)
        }, kind = KitButtonKind.Secondary, enabled = !form.working, tag = "code-cancel")
    }
}

internal fun LazyListScope.privacyPage(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val privacy = state.privacy
    item("privacy-policy") {
        SettingsGroup(stringResource(R.string.settings_contact_requests)) {
            for (policy in ContactRequestPolicy.entries) {
                KitRow(
                    stringResource(
                        if (policy == ContactRequestPolicy.MANUAL) R.string.settings_requests_manual else R.string.settings_requests_auto
                    ),
                    { onAction(SettingsAction.RequestPolicy(policy)) },
                    value = stringResource(
                        if (policy == ContactRequestPolicy.MANUAL) {
                            R.string.settings_requests_manual_help
                        } else {
                            R.string.settings_requests_auto_help
                        }
                    ),
                    trailing = if (privacy.policy == policy) KitRowTrailing.Check else KitRowTrailing.None,
                    enabled = !privacy.loading && privacy.policy != null,
                    divider = policy != ContactRequestPolicy.entries.last(),
                    testTag = "policy:" + policy.wire
                )
            }
        }
    }
    privacy.problem?.let { problem -> item("privacy-error") { Text(problem.render()) } }
    item("open-account") {
        SettingsGroup(stringResource(R.string.settings_open_account_mode)) {
            KitRow(
                stringResource(R.string.settings_open_account_mode),
                {
                },
                value = stringResource(
                    R.string.settings_blocked_by_sdk
                ),
                locked = true,
                trailing = KitRowTrailing.None,
                testTag = "open-account"
            )
            KitRow(
                stringResource(R.string.settings_username),
                {
                },
                value = stringResource(
                    R.string.settings_blocked_by_sdk
                ),
                locked = true,
                trailing = KitRowTrailing.None,
                divider = false,
                testTag = "username"
            )
        }
    }
}

internal fun LazyListScope.pairingPage(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    drawInvitation: (Int, (CharArray) -> Unit) -> Unit,
    drawInvitationQr: (((Int, Int) -> Boolean) -> Unit) -> Unit
) {
    val pairing = state.pairing
    item("pairing-help") {
        KitSecurityWarning(
            stringResource(R.string.settings_pairing_title),
            stringResource(R.string.settings_pairing_help),
            KitWarningTone.Warning
        )
    }
    pairing.problem?.let { problem -> item("pairing-error") { Text(problem.render(), Modifier.testTag("pairing-error")) } }
    when (pairing.step) {
        PairingStep.IDLE, PairingStep.ENDED -> {
            pairing.ended?.let { end -> item("pairing-ended") { PairingEnded(end, pairing.result) } }
            item("pairing-start") {
                KitButton(stringResource(R.string.settings_pairing_start), {
                    onAction(SettingsAction.StartPairing)
                }, enabled = state.accountLabel != null, tag = "pairing-start")
            }
        }
        PairingStep.STARTING, PairingStep.WORKING -> item("pairing-working") {
            KitEmpty("loader", stringResource(R.string.settings_pairing_working), "")
        }
        PairingStep.INVITED -> item("pairing-invitation") { Invitation(pairing, onAction, drawInvitation, drawInvitationQr) }
        PairingStep.COMPARE -> item("pairing-compare") {
            Column(verticalArrangement = Arrangement.spacedBy(LocalRFTheme.current.gap.dp)) {
                Text(stringResource(R.string.settings_pairing_compare))
                LtrCode(pairing.emoji.joinToString(SPACE) { sasGlyph(it) }, size = 28f, centered = true, tag = "pairing-emoji")
                LtrCode(pairing.emoji.joinToString(SPACE) { it.toString() }, centered = true, tag = "pairing-emoji-index")
                KitButton(
                    stringResource(R.string.settings_pairing_match),
                    { onAction(SettingsAction.ComparePairing(true)) },
                    tag = "pairing-match"
                )
                KitButton(stringResource(R.string.settings_pairing_mismatch), {
                    onAction(SettingsAction.ComparePairing(false))
                }, kind = KitButtonKind.Destructive, tag = "pairing-mismatch")
            }
        }
        PairingStep.REVIEW -> item("pairing-review") {
            Column(verticalArrangement = Arrangement.spacedBy(LocalRFTheme.current.gap.dp)) {
                KitApprovalCard(
                    requester = pairing.peerName ?: stringResource(R.string.settings_pairing_unnamed),
                    status = stringResource(R.string.settings_pairing_waiting),
                    title = stringResource(R.string.settings_pairing_review),
                    scopeLabel = stringResource(R.string.settings_pairing_rights),
                    scopeDetail = pairing.capabilities.joinToString(stringResource(R.string.settings_list_separator)),
                    decline = stringResource(R.string.settings_pairing_decline),
                    approve = stringResource(R.string.settings_pairing_approve),
                    onDecline = { onAction(SettingsAction.DeclinePairing) },
                    onApprove = { onAction(SettingsAction.ApprovePairing) },
                    actions = !pairing.confirmApprove,
                    tag = "pairing-approval"
                )
                pairing.peerDevice?.let { LtrCode(it, tag = "pairing-peer-device") }
                pairing.leaseExpiresAt?.let { Text(UiText.DateTime(it).render()) }
                if (pairing.confirmApprove) {
                    KitSecurityWarning(
                        stringResource(R.string.settings_pairing_confirm_title),
                        stringResource(R.string.settings_pairing_confirm_body),
                        KitWarningTone.Danger
                    )
                    KitButton(stringResource(R.string.settings_pairing_approve), {
                        onAction(SettingsAction.ConfirmApprovePairing)
                    }, tag = "pairing-confirm-approve")
                    KitButton(stringResource(R.string.settings_pairing_decline), {
                        onAction(SettingsAction.DeclinePairing)
                    }, kind = KitButtonKind.Secondary, tag = "pairing-decline")
                }
            }
        }
    }
    if (pairing.step !in setOf(PairingStep.IDLE, PairingStep.ENDED, PairingStep.STARTING)) {
        item("pairing-cancel") {
            KitButton(stringResource(R.string.settings_pairing_cancel), {
                onAction(SettingsAction.CancelPairing)
            }, kind = KitButtonKind.Secondary, tag = "pairing-cancel")
        }
    }
}

/**
 * The show-once invitation as a QR code, or as numbered text chunks on request (or when it does not fit a
 * QR code); either is covered whenever the window loses focus (recents, overlays, other windows).
 */
@Composable
private fun Invitation(
    pairing: SettingsPairing,
    onAction: (SettingsAction) -> Unit,
    drawInvitation: (Int, (CharArray) -> Unit) -> Unit,
    drawInvitationQr: (((Int, Int) -> Boolean) -> Unit) -> Unit
) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val asQr = pairing.invitationModules > 0 && !pairing.invitationAsText
    Column(verticalArrangement = Arrangement.spacedBy(LocalRFTheme.current.gap.dp)) {
        Text(stringResource(if (asQr) R.string.settings_pairing_invitation_qr else R.string.settings_pairing_invitation))
        when {
            !focused || pairing.invitationChunks == 0 ->
                KitEmpty("eye", stringResource(R.string.settings_pairing_covered), "", modifier = Modifier.testTag("pairing-covered"))
            asQr -> KitQrCode(
                pairing.invitationModules,
                drawInvitationQr,
                stringResource(R.string.settings_pairing_qr_description),
                tag = "invitation-qr"
            )
            else -> LtrSecretWords(
                pairing.invitationChunks,
                drawInvitation,
                Modifier.testTag("pairing-invitation"),
                tag = "invitation-chunk"
            )
        }
        if (pairing.invitationModules > 0 && pairing.invitationChunks > 0) {
            KitButton(
                stringResource(if (asQr) R.string.settings_pairing_show_text else R.string.settings_pairing_show_qr),
                { onAction(SettingsAction.InvitationAsText(asQr)) },
                kind = KitButtonKind.Secondary,
                tag = "pairing-invitation-format"
            )
        }
        pairing.expiresAt?.let { Text(stringResource(R.string.settings_pairing_expires, UiText.DateTime(it).render())) }
    }
}

@Composable
private fun PairingEnded(end: PairingEnd, result: String?) {
    val text = stringResource(
        when (end) {
            PairingEnd.COMPLETED -> R.string.settings_pairing_completed
            PairingEnd.CANCELLED -> R.string.settings_pairing_cancelled
            PairingEnd.EXPIRED -> R.string.settings_pairing_expired
            PairingEnd.REJECTED -> R.string.settings_pairing_rejected
            PairingEnd.FAILED -> R.string.settings_pairing_failed
        }
    )
    Column(Modifier.testTag("pairing-ended")) {
        Text(text)
        if (end != PairingEnd.COMPLETED) result?.let { LtrCode(it, tag = "pairing-result") }
    }
}

internal fun LazyListScope.languagePage(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    choice: @Composable (String, String, Boolean, Boolean, () -> Unit) -> Unit
) {
    item("language-help") { Text(stringResource(R.string.settings_language_help)) }
    item("language:system") {
        choice("language:", stringResource(R.string.settings_system), state.selectedLanguage == AppLanguage.SYSTEM, !state.savingLanguage) {
            onAction(SettingsAction.Language(AppLanguage.SYSTEM))
        }
    }
    val translated = state.languages.map { it.tag }.toSet()
    for (tag in LAUNCH_LANGUAGES) {
        item("language:$tag") {
            if (tag in translated) {
                choice("language:$tag", languageName(tag), state.selectedLanguage.tag == tag, !state.savingLanguage) {
                    onAction(SettingsAction.Language(AppLanguage(tag)))
                }
            } else {
                // Wired, not translated: listed with its status, never selectable with borrowed English text.
                KitRow(
                    languageName(tag),
                    {
                    },
                    value = stringResource(
                        R.string.settings_language_untranslated
                    ),
                    locked = true,
                    trailing = KitRowTrailing.None,
                    testTag = "language:$tag"
                )
            }
        }
    }
    item("apply-language") {
        KitButton(
            stringResource(R.string.settings_apply_language),
            { onAction(SettingsAction.ApplyLanguage) },
            kind = KitButtonKind.Secondary,
            enabled = !state.savingLanguage && state.selectedLanguage != state.savedLanguage,
            tag = "apply-language"
        )
    }
}

internal fun LazyListScope.diagnosticsPage(
    state: SettingsState,
    about: @Composable () -> Unit,
    statusRow: @Composable (String, UiText) -> Unit
) {
    state.statusProblem?.let { problem -> item("status-error") { Text(problem.render()) } }
    state.status?.let { status ->
        item("secure-sessions") { statusRow(stringResource(R.string.settings_sessions), status.sessions) }
        item("sync") { statusRow(stringResource(R.string.settings_sync), status.sync) }
        item("node") { statusRow(stringResource(R.string.settings_node), status.node) }
        item("last-run") { statusRow(stringResource(R.string.settings_last_run), status.lastRun) }
    }
    item("about") { about() }
}

/** A language's own name in that language (its endonym), as language pickers show it. */
internal fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** The fifteen launch languages (master plan, mobile item 4), in the plan's order. */
internal val LAUNCH_LANGUAGES = listOf("en", "zh-cn", "zh-tw", "ja", "ko", "es", "fr", "de", "pt-br", "ru", "ar", "hi", "id", "tr", "it")
    .map { Locale.forLanguageTag(it).toLanguageTag() }

/** Groups an [LtrCode] value: codes are split into groups at spaces. */
private val SPACE = Char(0x20).toString()

/** Digit keypad rows; [KEY_NONE] leaves a gap, [KEY_DELETE] removes the last digit. */
private const val KEY_NONE = -1
private const val KEY_DELETE = -2
private val KEYPAD = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(KEY_NONE, 0, KEY_DELETE))

/** The SDK's short-authentication-string table (`ramflux-crypto` `custody/pairing/sas.rs`), by index, as code points. */
private val SAS_CODE_POINTS = intArrayOf(
    0x1F436, 0x1F431, 0x1F981, 0x1F434, 0x1F984, 0x1F437, 0x1F418, 0x1F430,
    0x1F43C, 0x1F413, 0x1F427, 0x1F422, 0x1F41F, 0x1F419, 0x1F98B, 0x1F337,
    0x1F333, 0x1F335, 0x1F344, 0x1F30F, 0x1F319, 0x2601, 0x1F525, 0x1F34C,
    0x1F34E, 0x1F353, 0x1F33D, 0x1F355, 0x1F382, 0x2764, 0x1F600, 0x1F916,
    0x1F3A9, 0x1F453, 0x1F527, 0x1F385, 0x1F44D, 0x2602, 0x231B, 0x23F0,
    0x1F381, 0x1F4A1, 0x1F4D5, 0x270F, 0x1F4CE, 0x2702, 0x1F512, 0x1F511,
    0x1F528, 0x260E, 0x1F3C1, 0x1F682, 0x1F6B2, 0x2708, 0x1F680, 0x1F3C6,
    0x26BD, 0x1F3B8, 0x1F3BA, 0x1F514, 0x2693, 0x1F3A7, 0x1F4C1, 0x1F4CC
)

/** Entries the SDK table writes with the emoji presentation selector (U+FE0F). */
private val SAS_PRESENTATION = setOf(21, 29, 37, 43, 45, 49, 53)

/** The glyph of SAS index [index]; an out-of-range index shows its number instead of a guess. */
internal fun sasGlyph(index: Int): String {
    val point = SAS_CODE_POINTS.getOrNull(index) ?: return index.toString()
    val glyph = String(Character.toChars(point))
    return if (index in SAS_PRESENTATION) glyph + Char(0xFE0F) else glyph
}
