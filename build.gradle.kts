import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

version = "0.1.1"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

dependencies {
    compileOnly("org.jetbrains.compose.desktop:desktop:1.12.2")
    implementation("io.github.moriafly:salt-ui:3.0.0-beta03")
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("net.java.dev.jna:jna:5.18.1")
    testRuntimeOnly("net.java.dev.jna:jna:5.18.1")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.skiko")
    exclude(group = "androidx.lifecycle")
    exclude(group = "org.jetbrains.androidx.lifecycle")
    exclude(group = "net.java.dev.jna")
    listOf("animation", "desktop", "foundation", "material", "material3", "runtime", "ui", "components")
        .forEach { component ->
            exclude(group = "org.jetbrains.compose.$component")
            exclude(group = "androidx.compose.$component")
        }
}

sourceSets.test { java.setSrcDirs(listOf("test/java")) }

spmod {
    PluginId = "dev.gaboron.spwtrackmix"
    PluginClass = "dev.gaboron.spwtrackmix.TrackMixPlugin"
    PluginName = "SPW Track Mix"
    PluginProvider = "GaBoron"
    PluginVersion = project.version.toString()
    PluginDescription = "本地 AI 六轨音源分离与 SPW 实时混音"
    PluginHasConfig = true
    PluginPermissions = listOf(
        com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM,
        com.xuncorp.spw.workshop.gradle.PluginPermission.LIBRARY_READ
    )
}

tasks.named<Zip>("plugin") {
    archiveFileName.set("spw-track-mix-${project.version}.spmod")
    from("README.md")
    into("licenses") { from("licenses") }
}

val pcmCheck = tasks.register<JavaExec>("pcmCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.gaboron.spwtrackmix.PcmChecks"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val bassCheck = tasks.register<JavaExec>("bassCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    val archive = providers.gradleProperty("spwArchive").orNull
    classpath = files(archive ?: emptyList<Any>()) + sourceSets.test.get().runtimeClasspath
    mainClass = "dev.gaboron.spwtrackmix.BassChecks"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    providers.gradleProperty("bassDirectory").orNull?.let {
        dependsOn("testTrack")
        args(it, layout.buildDirectory.file("fixtures/spw-track-mix-test.wav").get().asFile.absolutePath)
    }
    onlyIf { providers.gradleProperty("bassDirectory").isPresent }
}

tasks.register<JavaExec>("testTrack") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.gaboron.spwtrackmix.ProbeWave"
    args(layout.buildDirectory.file("fixtures/spw-track-mix-test.wav").get().asFile.absolutePath)
}

val hostBindingCheck = tasks.register<JavaExec>("hostBindingCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    val archive = providers.gradleProperty("spwArchive").orNull
    classpath = files(archive ?: emptyList<Any>()) + sourceSets.test.get().runtimeClasspath
    mainClass = "dev.gaboron.spwtrackmix.HostBindingChecks"
    if (archive != null) args(archive)
    onlyIf { archive != null }
}

tasks.test { enabled = false }
tasks.check { dependsOn(pcmCheck, bassCheck, hostBindingCheck) }
