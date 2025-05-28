// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.gms.google.services) apply false
    id("com.github.ben-manes.versions") version "0.52.0"
    id("nl.littlerobots.version-catalog-update") version "1.0.0"
    alias(libs.plugins.android.library) apply false
}

ktlint {
    version.set("1.6.0") // 最新バージョンを指定
    verbose.set(true)

    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}
