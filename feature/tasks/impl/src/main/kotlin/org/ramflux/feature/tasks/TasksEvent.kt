// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
sealed interface TasksEvent {
    data object Back : TasksEvent
    data object SetupAccount : TasksEvent
}
