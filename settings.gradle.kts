// SPDX-License-Identifier: AGPL-3.0-or-later
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PingMe"

include(":app")

// The Go bridge (BUILD_PLAN.md P3.1): libgm compiled with gomobile, published as an AAR.
include(":gobridge")

include(
    ":core:model",
    ":core:store",
    ":core:connector-api",
    // Shared test code: ConnectorContractTest, used by every connector's tests.
    ":core:connector-contract",
    ":core:service",
    ":core:ui",
)

include(
    ":connectors:demo",
    ":connectors:gmessages",
    ":connectors:sms",
    ":connectors:whatsapp",
    ":connectors:telegram",
    ":connectors:signal",
    ":connectors:gvoice",
    ":connectors:instagram",
    ":connectors:messenger",
    ":connectors:fbpage",
)
