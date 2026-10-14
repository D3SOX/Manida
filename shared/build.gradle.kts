plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "io.github.d3sox.musicplayer.shared"
        compileSdk = 37
        minSdk = 31
        withHostTest {}
        androidResources { enable = true }
    }
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines)
            implementation(libs.serialization.json)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
            implementation(libs.material3)
            implementation(compose.materialIconsExtended)
            implementation("sh.calvin.reorderable:reorderable:3.1.0")
            implementation(libs.material.kolor)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.ui.test)
        }
        getByName("androidMain") {
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jaudiotagger)
                implementation(libs.sshj)
                implementation(libs.bouncycastle.provider)
                implementation(libs.bouncycastle.pkix)
                implementation(libs.activity.compose)
                implementation(libs.camera.camera2)
                implementation(libs.camera.lifecycle)
                implementation(libs.camera.view)
                implementation(libs.zxing.core)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.session)
            }
        }
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jna)
                implementation(libs.zxing.core)
                api(libs.dbus.core)
                implementation(libs.dbus.unix)
                implementation(libs.jaudiotagger)
                implementation(libs.sshj)
                implementation(libs.bouncycastle.provider)
                implementation(libs.bouncycastle.pkix)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

compose.resources { packageOfResClass = "io.github.d3sox.musicplayer.resources" }

val visualizerNative = tasks.register<Exec>("buildVisualizerNative") {
    inputs.property("buildType", providers.environmentVariable("MANIDA_NATIVE_BUILD_TYPE").orElse("Release"))
    inputs.dir(rootProject.file("native"))
    inputs.file(rootProject.file("scripts/build-visualizer.py"))
    outputs.dir(layout.buildDirectory.dir("native/runtime"))
    environment("JAVA_HOME", javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().metadata.installationPath.asFile.absolutePath)
    commandLine("python3", rootProject.file("scripts/build-visualizer.py"), "--output", layout.buildDirectory.dir("native/runtime").get().asFile)
}

tasks.withType<Test>().configureEach {
    systemProperty("musicplayer.testRoot", rootDir.absolutePath)
    if (name == "desktopTest") {
        dependsOn(visualizerNative)
        systemProperty("java.library.path", layout.buildDirectory.dir("native/runtime").get().asFile.absolutePath)
        System.getenv("LD_LIBRARY_PATH")?.let { systemProperty("jna.library.path", it) }
    }
}

tasks.register<JavaExec>("pairedFixture") {
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(compilation.compileTaskProvider)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("io.github.d3sox.musicplayer.sync.PairedFixtureKt")
    args(rootProject.file(".test-environment/paired-fixture").absolutePath)
}
