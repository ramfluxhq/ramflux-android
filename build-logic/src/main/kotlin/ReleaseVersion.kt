// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import org.gradle.api.Project
import java.util.Properties

/** The shared product version, read from the repository's single `version.properties`. */
class ReleaseVersion(val name: String, val code: Int) {
    companion object {
        fun of(project: Project): ReleaseVersion {
            val props = Properties()
            project.rootProject.file("version.properties").inputStream().use { props.load(it) }
            return ReleaseVersion(
                requireNotNull(props.getProperty("versionName")) { "versionName missing in version.properties" },
                requireNotNull(props.getProperty("versionCode")) { "versionCode missing in version.properties" }.toInt()
            )
        }
    }
}
