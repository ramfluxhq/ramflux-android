// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins { id("ramflux.android.app") }

// The realrun build copies the card fixtures into its assets (card-fixtures/), where the card gallery reads them. The
// copy is made from the repository's card-fixtures/ (MANIFEST.json included); no other build type packages it.
abstract class StageCardFixtures : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun stage() {
        val target = output.get().asFile.resolve("card-fixtures")
        target.deleteRecursively()
        source.get().asFile.copyRecursively(target, overwrite = true)
    }
}

val stageCardFixtures = tasks.register<StageCardFixtures>("stageCardFixtures") {
    source.set(rootProject.layout.projectDirectory.dir("card-fixtures"))
    output.set(layout.buildDirectory.dir("generated/card-fixtures"))
}

extensions.getByType<ApplicationAndroidComponentsExtension>().let { components ->
    components.onVariants(components.selector().withBuildType("realrun")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageCardFixtures) { it.output }
    }
}

android {
    bundle { language { enableSplit = false } }
    // The locale config lists exactly the locales that have resources (res/resources.properties names
    // the default), so adding a language is a resources-only change.
    androidResources { generateLocaleConfig = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/screenshot/kotlin"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/fixtures/conversations/kotlin"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/fixtures/chat/kotlin"))
    // The test-only card runtime binding (scripts/build-native.sh with RAMFLUX_CARD_FIXTURE=1). Realrun builds only.
    sourceSets.getByName("realrun").jniLibs.srcDir(rootProject.file("core/sdk/build/generated/native/card-fixture"))
}
// The edition selects the push modules the app links (push/README.md); only `international` exists so far.
val edition = providers.gradleProperty("ramflux.edition").orElse("international").get()
dependencies {
    implementation(project(":push:impl"))
    when (edition) {
        "international" -> {
            implementation(project(":push:international:compat"))
            implementation(project(":push:international:fcm"))
        }
        else -> throw GradleException("edition not implemented: $edition (ramflux.edition must be international)")
    }
    implementation(project(":feature:permissions:impl"))
    implementation(project(":feature:cards:impl"))
    // The gallery (realrun source set) draws cards with the same renderer as the chat timeline.
    implementation(project(":feature:cards:renderer"))
    implementation(project(":feature:tasks:impl"))
    implementation(project(":feature:agents:impl"))
    implementation(project(":feature:calls:impl"))
    implementation(project(":feature:groups:impl"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // The opt-in real-run suite (org.ramflux.android.realrun, scripts/realrun.sh); no CI task runs it.
    androidTestImplementation(libs.androidx.test.uiautomator)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.test)
    testImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.test.manifest)
    implementation(project(":core:data"))
    implementation(project(":feature:settings:impl"))
    implementation(project(":feature:contacts:impl"))
    implementation(project(":feature:chat:impl"))
    implementation(project(":feature:conversations:impl"))
    implementation(project(":feature:onboarding:impl"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:app-shell:impl"))
}
