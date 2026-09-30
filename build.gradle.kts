// SPDX-License-Identifier: AGPL-3.0-or-later
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    // Applied here too, so the root build scripts are checked as well.
    alias(libs.plugins.ktlint)
}

// Shared by every module. The app targets the newest stable platform (API 37,
// minor 2) and runs on Android 10 and up (DESIGN.md 6.7).
val sdkRelease = 37
val sdkMinor = 2
val minSdkLevel = 29
val javaVersion = JavaVersion.VERSION_17

val expressiveOptIns = listOf(
    "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    "androidx.compose.material3.ExperimentalMaterial3Api",
)

// Static checks (BUILD_PLAN.md P0.5). `./gradlew check` runs ktlintCheck, detekt,
// Android lint, and the unit tests in every module.
val ktlintVersion = libs.versions.ktlint.cli.get()
val composeRulesDetekt = libs.compose.rules.detekt
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
    }
}

subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
    dependencies.add("detektPlugins", composeRulesDetekt)

    listOf("com.android.application", "com.android.library").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            extensions.configure<CommonExtension>("android") {
                compileSdk {
                    version = release(sdkRelease) { minorApiLevel = sdkMinor }
                }
                // CommonExtension in AGP 9 exposes these as getters only.
                defaultConfig.minSdk = minSdkLevel
                compileOptions.apply {
                    sourceCompatibility = javaVersion
                    targetCompatibility = javaVersion
                    isCoreLibraryDesugaringEnabled = true
                }
            }
            dependencies.add("coreLibraryDesugaring", libs.desugar.jdk.libs)
        }
    }

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            defaultConfig {
                targetSdk {
                    version = release(sdkRelease)
                }
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }

    // The Expressive opt-ins apply wherever Compose is compiled.
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.optIn.addAll(expressiveOptIns)
        }
    }
}
