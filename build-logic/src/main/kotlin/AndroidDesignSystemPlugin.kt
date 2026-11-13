// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class ThemeCatalogTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val themes: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val schema: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val compiler: RegularFileProperty
    @get:OutputDirectory abstract val javaOutput: DirectoryProperty
    @get:OutputDirectory abstract val resourcesOutput: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations
    @TaskAction fun compile() {
        execOperations.exec {
            commandLine("python3", compiler.get().asFile, "--themes", themes.get().asFile,
                "--schema", schema.get().asFile, "--java", javaOutput.get().asFile, "--res", resourcesOutput.get().asFile)
        }
    }
}
class AndroidDesignSystemPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ramflux.android.compose")
        val catalog = tasks.register<ThemeCatalogTask>("generateThemeCatalog") {
            themes.set(layout.projectDirectory.dir("src/main/assets/ramflux/themes"))
            schema.set(layout.projectDirectory.file("src/main/kotlin/org/ramflux/generated/RFTokens.kt"))
            compiler.set(rootProject.layout.projectDirectory.file("scripts/build-theme-catalog.py"))
            javaOutput.set(layout.buildDirectory.dir("generated/themeCatalog/java"))
            resourcesOutput.set(layout.buildDirectory.dir("generated/themeCatalog/res"))
        }
        extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
            variant.sources.java?.addGeneratedSourceDirectory(catalog) { it.javaOutput }
            variant.sources.res?.addGeneratedSourceDirectory(catalog) { it.resourcesOutput }
        }
        Unit
    }
}
