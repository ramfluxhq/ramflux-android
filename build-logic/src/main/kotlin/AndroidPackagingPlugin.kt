// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** Build-only consumer: validates the AAR's real manifest and native APK packaging, without UI. */
class AndroidPackagingPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.getByType<ApplicationExtension>().apply {
            namespace = "org.ramflux.bridge.packaging"
            compileSdk = 36
            defaultConfig {
                applicationId = "org.ramflux.bridge.packaging"
                minSdk = 28
                targetSdk = 36
                versionCode = 1
                versionName = "0.1"
                ndk.abiFilters.add("arm64-v8a")
            }
            packaging.jniLibs.useLegacyPackaging = false
            lint { informational += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable") }
        }
        Unit
    }
}
