import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines)
}
val visualizerResources = tasks.register<Sync>("visualizerResources") {
    dependsOn(":shared:buildVisualizerNative")
    from(project(":shared").layout.buildDirectory.dir("native/runtime"))
    into(layout.buildDirectory.dir("visualizer-resources/common/native"))
}
tasks.matching { it.name == "prepareAppResources" || it.name == "createDistributable" || it.name == "run" }.configureEach { dependsOn(visualizerResources) }
compose.desktop {
    application {
        mainClass = "io.github.d3sox.musicplayer.desktop.MainKt"
        // The JDK 21 native launcher aborts during library shutdown on current Arch.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("visualizer-resources"))
            targetFormats(TargetFormat.Deb)
            packageName = "manida"
            packageVersion = "0.2.0"
            linux { iconFile.set(rootProject.file("packaging/manida.png")) }
            modules("java.sql", "java.net.http", "jdk.unsupported", "jdk.security.auth")
        }
    }
}
