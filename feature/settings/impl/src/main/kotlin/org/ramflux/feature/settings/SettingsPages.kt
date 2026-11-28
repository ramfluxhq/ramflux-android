// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.Locale
import org.ramflux.data.AccountCode
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
                value = state.accountLabel?.let { it.ifEmpty { stringResource(R.string.settings_account_unknown) } }
                    ?: stringResource(R.string.settings_account_needed_short),
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
                testTag = "permissions"
            )
            KitRow(
                stringResource(R.string.settings_delivery),
                { onAction(SettingsAction.Delivery) },
                icon = "bell",
                value = stringResource(R.string.settings_delivery_help),
                divider = false,
                testTag = "delivery"
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
                divider = false,
                testTag = "privacy"
            )
        }
    }
    if (state.codeChange.done) {
        item(
            "code-changed"
        ) { Text(stringResource(R.string.settings_code_changed), Modifier.testTag("code-changed")) }
    }
    if (state.codeChange.open || state.codeChange.working) item("code-form") { CodeChangeForm(state.codeChange, onAction) }
    item("account-exit") {
        SettingsGroup(stringResource(R.string.settings_account_exit)) {
            KitRow(
                stringResource(R.string.settings_sign_out),
                { onAction(SettingsAction.AccountExit(SettingsAccountExit.SIGN_OUT)) },
                icon = "user-minus",
                value = stringResource(R.string.settings_sign_out_help),
                enabled = state.accountLabel != null,
                testTag = "sign-out"
            )
            KitRow(
                stringResource(R.string.settings_remove_account),
                { onAction(SettingsAction.AccountExit(SettingsAccountExit.REMOVE)) },
                icon = "trash-2",
                value = stringResource(R.string.settings_remove_account_help),
                enabled = state.accountLabel != null,
                divider = false,
                testTag = "remove-account"
            )
        }
    }
    state.confirmExit?.let { exit ->
        val signOut = exit == SettingsAccountExit.SIGN_OUT
        item("confirm-exit") {
            Column(verticalArrangement = Arrangement.spacedBy(LocalRFTheme.current.gap.dp)) {
                // D-2: the confirmation names the account it acts on.
                val name = state.accountLabel.orEmpty().ifEmpty { stringResource(R.string.settings_account_unknown) }
                if (!signOut) Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("confirm-exit-name"))
                KitSecurityWarning(
                    if (signOut) {
                        stringResource(R.string.settings_sign_out_confirm_title, name)
                    } else {
                        stringResource(R.string.settings_remove_account_confirm_title)
                    },
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
}

@Composable
private fun CodeChangeForm(form: SettingsCodeChange, onAction: (SettingsAction) -> Unit) {
    val theme = LocalRFTheme.current
    // The keypad types into the first unfinished field: current, then new, then the repeat.
    val target = when {
        form.currentDigits < 6 -> 0
        form.nextDigits < 6 -> 1
        else -> 2
    }
    Column(Modifier.testTag("code-form"), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
        KitCodeField(stringResource(R.string.settings_code_current), form.currentDigits, focused = target == 0, tag = "code-current")
        KitCodeField(
            stringResource(R.string.settings_code_new),
            form.nextDigits,
            focused = target == 1,
            error = form.problem?.render(),
            helper = stringResource(R.string.settings_code_new_help),
            tag = "code-new"
        )
        KitCodeField(
            stringResource(R.string.settings_code_repeat),
            form.repeatDigits,
            focused = target == 2,
            error = if (form.mismatch) stringResource(R.string.settings_code_mismatch) else null,
            tag = "code-repeat"
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
                                { onAction(codeKey(target, form, null)) },
                                kind = KitButtonKind.Secondary,
                                enabled = !form.working,
                                tag = "code-delete"
                            )
                            else -> KitButton(
                                key.toString(),
                                { onAction(codeKey(target, form, '0' + key)) },
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
            enabled = !form.working && form.currentDigits == 6 && form.nextDigits == 6 && form.repeatDigits == 6 && !form.mismatch,
            tag = "code-submit"
        )
        KitButton(stringResource(R.string.settings_cancel), {
            onAction(SettingsAction.CloseCodeChange)
        }, kind = KitButtonKind.Secondary, enabled = !form.working, tag = "code-cancel")
    }
}

/** The action for a keypad key: a digit goes to the target field; delete steps back to the new code from an empty repeat. */
private fun codeKey(target: Int, form: SettingsCodeChange, digit: Char?): SettingsAction = when {
    target == 2 && (digit != null || form.repeatDigits > 0) -> SettingsAction.RepeatDigit(digit)
    target == 2 -> SettingsAction.CodeDigit(true, null)
    else -> SettingsAction.CodeDigit(target == 1, digit)
}

internal fun LazyListScope.privacyPage(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val privacy = state.privacy
    item("privacy-policy") {
        val theme = LocalRFTheme.current
        val auto = privacy.policy == ContactRequestPolicy.AUTO_ACCEPT
        SettingsGroup(stringResource(R.string.settings_contact_requests)) {
            // One switch (D-15): off means every request is reviewed.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap / 2).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_requests_auto_switch))
                    Text(
                        stringResource(if (auto) R.string.settings_requests_auto_help else R.string.settings_requests_manual_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.secondary
                    )
                }
                KitSwitch(
                    checked = auto,
                    onChecked = {
                        onAction(
                            SettingsAction.RequestPolicy(if (it) ContactRequestPolicy.AUTO_ACCEPT else ContactRequestPolicy.MANUAL)
                        )
                    },
                    enabled = !privacy.loading && privacy.policy != null,
                    label = stringResource(R.string.settings_requests_auto_switch),
                    tag = "policy:auto",
                    stateKnown = privacy.policy != null
                )
            }
        }
    }
    privacy.problem?.let { problem -> item("privacy-error") { Text(problem.render()) } }
    item("app-lock-entry") {
        SettingsGroup(stringResource(R.string.settings_app_lock)) {
            KitRow(
                stringResource(R.string.settings_app_lock),
                { onAction(SettingsAction.Page(SettingsPage.APP_LOCK)) },
                value = stringResource(if (state.appLock.enabled) R.string.settings_app_lock_on else R.string.settings_app_lock_off),
                divider = false,
                testTag = "app-lock"
            )
        }
    }
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
            // The directory's answer for this account (`username.status`), or its typed refusal; the page claims or changes it.
            KitRow(
                stringResource(R.string.settings_username),
                { onAction(SettingsAction.Page(SettingsPage.USERNAME)) },
                value = state.username?.render(),
                enabled = state.accountLabel != null,
                divider = false,
                testTag = "username"
            )
        }
    }
}

/**
 * The username page: the state the directory reports for this account, then the claim or change form. The recovery phrase
 * is typed into a masked field and handed to the view model as characters; it is never kept in state.
 */
internal fun LazyListScope.usernamePage(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val form = state.usernameClaim
    item("username-state") {
        SettingsGroup(stringResource(R.string.settings_username_title)) {
            KitRow(
                stringResource(R.string.settings_username),
                {},
                value = state.username?.render(),
                locked = true,
                trailing = KitRowTrailing.None,
                divider = false,
                testTag = "username-state"
            )
        }
    }
    item("username-actions", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            form.outcome?.let { Text(it.render(), color = theme.secondary) }
            if (form.step == SettingsUsernameStep.CLOSED) {
                KitButton(
                    stringResource(R.string.settings_username_claim),
                    { onAction(SettingsAction.OpenUsernameClaim) },
                    Modifier.fillMaxWidth(),
                    enabled = state.accountLabel != null && !form.working,
                    tag = "username-claim"
                )
            }
        }
    }
    if (form.step == SettingsUsernameStep.NAME) {
        item("username-name", contentType = "body") {
            val theme = LocalRFTheme.current
            Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitTextField(
                    form.name,
                    { onAction(SettingsAction.UsernameName(it)) },
                    label = stringResource(R.string.settings_username_name_label),
                    placeholder = stringResource(R.string.settings_username_name_placeholder),
                    error = form.problem?.render(),
                    helper = stringResource(R.string.settings_username_name_help),
                    enabled = !form.working,
                    tag = "username-name"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                    Box(Modifier.weight(1f)) {
                        KitButton(stringResource(R.string.settings_username_next), {
                            onAction(SettingsAction.UsernameNext)
                        }, Modifier.fillMaxWidth(), tag = "username-next")
                    }
                    Box(Modifier.weight(1f)) {
                        KitButton(
                            stringResource(R.string.settings_username_cancel),
                            { onAction(SettingsAction.CancelUsername) },
                            Modifier.fillMaxWidth(),
                            kind = KitButtonKind.Secondary,
                            tag = "username-cancel"
                        )
                    }
                }
            }
        }
    }
    if (form.step == SettingsUsernameStep.PHRASE) item("username-phrase", contentType = "body") { UsernamePhraseForm(form, onAction) }
}

/** The recovery-phrase step of the claim. The typed text lives only in this composable until it is handed over as characters. */
@Composable
private fun UsernamePhraseForm(form: SettingsUsernameClaim, onAction: (SettingsAction) -> Unit) {
    val theme = LocalRFTheme.current
    var phrase by remember { mutableStateOf("") }
    Column(
        Modifier.padding(horizontal = theme.gap.dp).testTag("username-phrase-form"),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        Text(stringResource(R.string.settings_username_phrase_title))
        KitTextField(
            phrase,
            { phrase = it },
            label = stringResource(R.string.settings_username_phrase_label),
            error = form.problem?.render(),
            helper = stringResource(R.string.settings_username_phrase_help),
            enabled = !form.working,
            tag = "username-phrase",
            secure = true
        )
        if (form.working) Text(stringResource(R.string.settings_username_publishing), color = theme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            Box(Modifier.weight(1f)) {
                KitButton(
                    stringResource(R.string.settings_username_confirm),
                    {
                        val chars = phrase.toCharArray()
                        phrase = ""
                        onAction(SettingsAction.ClaimUsername(chars))
                    },
                    Modifier.fillMaxWidth(),
                    enabled = phrase.isNotBlank() && !form.working,
                    tag = "username-confirm"
                )
            }
            Box(Modifier.weight(1f)) {
                KitButton(
                    stringResource(R.string.settings_username_cancel),
                    {
                        phrase = ""
                        onAction(SettingsAction.CancelUsername)
                    },
                    Modifier.fillMaxWidth(),
                    kind = KitButtonKind.Secondary,
                    enabled = !form.working,
                    tag = "username-cancel-phrase"
                )
            }
        }
    }
}

/**
 * The app lock page. The switch and the timeouts wait for the first read and stay off while the SDK reports the lock
 * unavailable on this device; the biometric row is shown disabled until the core offers presence unlock.
 */
internal fun LazyListScope.appLockPage(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val lock = state.appLock
    val usable = lock.loaded && !lock.loading && lock.unavailable == null && !lock.reauthOpen
    // Turning the lock on needs no code; turning it off is a weakening, so it waits out a throttle.
    val switchUsable = usable && (!lock.enabled || lock.waitSecs == 0L)
    item("app-lock-switch") {
        val theme = LocalRFTheme.current
        SettingsGroup(stringResource(R.string.settings_app_lock_title)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap / 2).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_app_lock_switch), Modifier.weight(1f))
                KitSwitch(
                    checked = lock.enabled,
                    onChecked = { onAction(SettingsAction.SetAppLock(it)) },
                    enabled = switchUsable,
                    label = stringResource(R.string.settings_app_lock_switch),
                    tag = "app-lock-switch",
                    stateKnown = lock.loaded
                )
            }
        }
    }
    item("app-lock-timeouts") {
        SettingsGroup(stringResource(R.string.settings_app_lock_timeout)) {
            APP_LOCK_TIMEOUTS.forEachIndexed { index, timeout ->
                KitRow(
                    stringResource(appLockTimeoutLabel(timeout)),
                    { onAction(SettingsAction.AppLockTimeout(timeout)) },
                    trailing = if (lock.timeoutMs == timeout) KitRowTrailing.Check else KitRowTrailing.None,
                    // A longer timeout weakens the lock: it waits out a throttle. A shorter one needs no code.
                    enabled = usable && lock.enabled && (timeout <= lock.timeoutMs || lock.waitSecs == 0L),
                    divider = index != APP_LOCK_TIMEOUTS.lastIndex,
                    testTag = "app-lock-timeout:$timeout"
                )
            }
        }
    }
    item("app-lock-now") {
        SettingsGroup(stringResource(R.string.settings_lock_now)) {
            KitRow(
                stringResource(R.string.settings_lock_now),
                { onAction(SettingsAction.Lock) },
                icon = "lock",
                value = stringResource(R.string.settings_lock_account_help),
                // The one lock entry: it needs the app lock on, so the two lock concepts are not shown side by side.
                enabled = usable && lock.enabled && state.accountLabel != null,
                divider = false,
                testTag = "lock-account"
            )
        }
    }
    item("app-lock-biometric") {
        SettingsGroup(stringResource(R.string.settings_app_lock_biometric_group)) {
            KitRow(
                stringResource(R.string.settings_app_lock_biometric),
                {},
                value = stringResource(R.string.settings_app_lock_not_available),
                enabled = false,
                trailing = KitRowTrailing.None,
                divider = false,
                testTag = "app-lock-biometric"
            )
        }
    }
    item("app-lock-explain") {
        Text(stringResource(R.string.settings_app_lock_explain), style = MaterialTheme.typography.bodyMedium)
    }
    lock.unavailable?.let { message -> item("app-lock-unavailable") { Text(message.render()) } }
    lock.problem?.let { message -> item("app-lock-error") { Text(message.render()) } }
    if (lock.wrongCode) {
        item("app-lock-wrong-code") {
            Text(stringResource(R.string.settings_app_lock_wrong_code), Modifier.testTag("app-lock-wrong-code"))
        }
    }
    if (lock.notChanged) {
        item("app-lock-not-changed") {
            Text(stringResource(R.string.settings_app_lock_not_changed), Modifier.testTag("app-lock-not-changed"))
        }
    }
    if (lock.waitSecs > 0L) {
        item("app-lock-wait") {
            val wait = stringResource(
                R.string.settings_app_lock_countdown,
                (lock.waitSecs / 60).toString().padStart(2, '0'),
                (lock.waitSecs % 60).toString().padStart(2, '0')
            )
            Text(stringResource(R.string.settings_app_lock_wait, wait), Modifier.testTag("app-lock-wait"))
        }
    }
    if (lock.exhausted) {
        item("app-lock-exhausted") {
            Column(verticalArrangement = Arrangement.spacedBy(LocalRFTheme.current.gap.dp)) {
                Text(stringResource(R.string.settings_app_lock_exhausted), Modifier.testTag("app-lock-exhausted"))
                KitButton(
                    stringResource(R.string.settings_app_lock_restore),
                    { onAction(SettingsAction.RecoverAccount) },
                    Modifier.fillMaxWidth(),
                    tag = "app-lock-restore"
                )
            }
        }
    }
    if (lock.reauthOpen) {
        item("app-lock-reauth") {
            AppLockReauthSheet(lock, onAction)
        }
    }
}

/**
 * Whether the account-code sheet is marked secure. The app provides `false` only in the realrun build (test-only,
 * never shipped) because UI automation cannot read a secure window; every other build keeps it secure.
 */
val LocalAppLockReauthSecure = staticCompositionLocalOf { true }

/**
 * The account-code sheet for a change that weakens the lock. It is its own window, so screenshots and recordings of it
 * are refused; the digits go straight to the view model, and cancelling or dismissing it refuses the request.
 */
@Composable
private fun AppLockReauthSheet(lock: SettingsAppLock, onAction: (SettingsAction) -> Unit) {
    val theme = LocalRFTheme.current
    val secure = LocalAppLockReauthSecure.current
    Dialog(
        onDismissRequest = { onAction(SettingsAction.CancelReauth) },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff
        )
    ) {
        Column(
            // A Dialog is its own window: test tags reach the accessibility tree as ids only when set here too.
            Modifier.semantics { testTagsAsResourceId = true }.testTag("app-lock-reauth").padding(theme.gap.dp),
            verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            Text(stringResource(R.string.settings_app_lock_reauth_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (lock.reauthTurnOff) R.string.settings_app_lock_reauth_off else R.string.settings_app_lock_reauth_longer
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            KitCodeField(
                stringResource(R.string.settings_app_lock_code_label),
                lock.reauthDigits,
                focused = true,
                tag = "app-lock-code"
            )
            for (row in KEYPAD) {
                Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                    for (key in row) {
                        Box(Modifier.weight(1f)) {
                            when (key) {
                                KEY_NONE -> Unit
                                KEY_DELETE -> KitButton(
                                    stringResource(R.string.settings_code_delete),
                                    { onAction(SettingsAction.ReauthDigit(null)) },
                                    kind = KitButtonKind.Secondary,
                                    tag = "app-lock-code-delete"
                                )
                                else -> KitButton(
                                    key.toString(),
                                    { onAction(SettingsAction.ReauthDigit('0' + key)) },
                                    kind = KitButtonKind.Secondary,
                                    tag = "app-lock-code-key-$key"
                                )
                            }
                        }
                    }
                }
            }
            KitButton(
                stringResource(R.string.settings_app_lock_confirm),
                { onAction(SettingsAction.SubmitReauth) },
                enabled = lock.reauthDigits == AccountCode.DIGITS,
                tag = "app-lock-confirm"
            )
            KitButton(
                stringResource(R.string.settings_cancel),
                { onAction(SettingsAction.CancelReauth) },
                kind = KitButtonKind.Secondary,
                tag = "app-lock-cancel"
            )
        }
    }
}

private fun appLockTimeoutLabel(timeoutMs: Long): Int = when (timeoutMs) {
    0L -> R.string.settings_app_lock_immediately
    60_000L -> R.string.settings_app_lock_1min
    300_000L -> R.string.settings_app_lock_5min
    900_000L -> R.string.settings_app_lock_15min
    else -> R.string.settings_app_lock_1h
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
