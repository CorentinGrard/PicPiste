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

// Records a flying day: ./gradlew :core:record [-Pairfield=LFLE | -Pairfield=path/to/terrain.json]
// (Ctrl-C to stop). Without -Pairfield, the bundled airfield.
tasks.register<JavaExec>("record") {
    group = "picpiste"
    description = "Records live OGN beacons around an airfield into recordings/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fr.cvvm.picpiste.core.replay.RecordMainKt")
    val airfield = (findProperty("airfield") as String?)
        ?.let { a -> rootProject.file(a).takeIf { it.isFile }?.absolutePath ?: a }
        .orEmpty()
    args(rootProject.file("recordings").absolutePath, airfield)
}
