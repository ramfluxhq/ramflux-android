// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

class AndroidFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ramflux.android.compose")
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("implementation", libs.findLibrary("hilt-android").get())
        dependencies.add("implementation", libs.findLibrary("hilt-compose").get())
        dependencies.add("ksp", libs.findLibrary("hilt-compiler").get())
        Unit
    }
}
