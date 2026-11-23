// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.library") }
dependencies {
    api(project(":core:sdk"))
    // BLAKE3 for the SDK's direct conversation id (DirectConversationId); the same library :core:media uses.
    implementation(libs.bouncycastle.prov)
}
