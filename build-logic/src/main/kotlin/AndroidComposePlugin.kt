// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.gradle.LibraryExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

class AndroidComposePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ramflux.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        extensions.getByType<KtlintExtension>().filter { exclude { it.file.path.contains("/generated/") } }
        extensions.getByType<LibraryExtension>().buildFeatures.compose = true
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("api", dependencies.platform(libs.findLibrary("compose-bom").get()))
        listOf("compose-ui", "compose-foundation", "compose-material", "compose-preview", "lifecycle-compose", "lifecycle-viewmodel").forEach {
            dependencies.add("api", libs.findLibrary(it).get())
        }
        dependencies.add("debugImplementation", libs.findLibrary("compose-tooling").get())
        Unit
    }
}
