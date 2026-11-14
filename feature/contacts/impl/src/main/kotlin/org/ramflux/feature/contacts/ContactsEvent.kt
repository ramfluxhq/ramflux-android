// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
sealed interface ContactsEvent {
    data object Back : ContactsEvent
    data object SetupAccount : ContactsEvent
}
