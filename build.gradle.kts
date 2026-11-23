plugins {
    java
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

dependencies {
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

sourceSets.test { java.setSrcDirs(listOf("test/java")) }

spmod {
    PluginId = "dev.gaboron.spwtrackmix"
    PluginClass = "dev.gaboron.spwtrackmix.TrackMixPlugin"
    PluginName = "SPW Track Mix"
    PluginProvider = "GaBoron"
    PluginVersion = project.version.toString()
    PluginDescription = "验证 SPW 单曲解码流上的六轨 PCM 混音，仅处理配套测试音频"
    PluginHasConfig = false
    PluginPermissions = listOf(com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM)
}

tasks.named<Zip>("plugin") {
    archiveFileName.set("spw-track-mix-${project.version}.spmod")
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
    classpath = sourceSets.test.get().runtimeClasspath
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

tasks.test { enabled = false }
tasks.check { dependsOn(pcmCheck, bassCheck) }
