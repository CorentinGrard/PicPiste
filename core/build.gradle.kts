plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

// Records a flying day around LFNL: ./gradlew :core:record  (Ctrl-C to stop)
tasks.register<JavaExec>("record") {
    group = "picpiste"
    description = "Records live OGN beacons around LFNL into recordings/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fr.cvvm.picpiste.core.replay.RecordMainKt")
    args(rootProject.file("recordings").absolutePath)
}
