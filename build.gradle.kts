// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// One configuration per jar. A shared configuration would keep only the highest version.
val robolectricAndroidAllSdk28 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val robolectricAndroidAllSdk35 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("robolectricAndroidAllSdk28", libs.android.all.sdk28)
    add("robolectricAndroidAllSdk35", libs.android.all.sdk35)
}

val robolectricJarDir = layout.projectDirectory.dir("robolectric-jars")
val robolectricJarNames = listOf(
    "android-all-instrumented-${libs.versions.androidAllSdk28.get()}.jar",
    "android-all-instrumented-${libs.versions.androidAllSdk35.get()}.jar",
)

tasks.register("prefetchRobolectricJars") {
    group = "verification"
    description = "Fetch the Robolectric android-all jars the unit tests use."
    outputs.files(robolectricJarNames.map { robolectricJarDir.file(it) })
    onlyIf {
        robolectricJarNames.any { name ->
            val file = robolectricJarDir.file(name).asFile
            !file.isFile || file.length() == 0L
        }
    }
    doLast {
        listOf(robolectricAndroidAllSdk28, robolectricAndroidAllSdk35).forEach { jars ->
            copy {
                from(jars)
                into(robolectricJarDir)
            }
        }
        robolectricJarNames.forEach { name ->
            val file = robolectricJarDir.file(name).asFile
            check(file.isFile && file.length() > 0L) { "Missing Robolectric jar $name" }
        }
    }
}
