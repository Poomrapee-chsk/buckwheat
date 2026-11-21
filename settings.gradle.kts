pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "buckwheat"
include(":app")

// PC tool comparing slip OCR setups on a folder of real slips, see tools/slipbench/README.md
include(":slipbench")
project(":slipbench").projectDir = file("tools/slipbench")
