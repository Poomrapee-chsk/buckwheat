// Slip OCR benchmark: runs the app's own slip OCR + parser on a folder of slip images, on a PC.
// ./gradlew :slipbench:run --args="/path/to/slips"   (see README.md)
plugins {
    // Version comes from the root build (Kotlin 2.2.0)
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.23.2")
}

// The app's slip OCR and parser are plain Kotlin: compile those exact files here
val appSources = tasks.register<Sync>("appSources") {
    from("../../app/src/main/java") {
        include("com/danilkinkin/buckwheat/slip/SlipParser.kt")
        include("com/danilkinkin/buckwheat/slip/ocr/**")
    }
    into(layout.buildDirectory.dir("appsrc"))
}

sourceSets {
    main {
        kotlin.srcDir(appSources)
    }
}

application {
    mainClass.set("SlipBenchKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.named<JavaExec>("run") {
    // Relative paths in --args are relative to where gradlew was started
    workingDir = rootProject.projectDir
}
