import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.register
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.fusedlibrary)
    `maven-publish`
}

androidFusedLibrary {
    namespace = "com.lightstick.sdk"
    minSdk = libs.versions.minSdk.get().toInt()
}

dependencies {
    include(project(":lightstick"))
    include(project(":lightstick-internal-core"))
}

val aggregatedSources by tasks.registering(Jar::class) {
    archiveBaseName.set("lightstick-sdk")
    archiveClassifier.set("sources")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/sources"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    fun maybeFrom(path: String) {
        val f = file(path)
        if (f.exists()) from(f)
    }
    maybeFrom(project(":lightstick").file("src/main/java").path)
    maybeFrom(project(":lightstick").file("src/main/kotlin").path)
    maybeFrom(project(":lightstick-internal-core").file("src/main/java").path)
    maybeFrom(project(":lightstick-internal-core").file("src/main/kotlin").path)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.lightstick"
            artifactId = "lightstick-sdk"
            version = "1.3.0"

            from(components["fusedLibraryComponent"])

            artifacts.forEach {
                if (it.extension == "aar") {
                    it.classifier = null
                    it.file.renameTo(File(it.file.parentFile, "${it.file.nameWithoutExtension}-${version}.aar"))
                }
            }

            artifact(aggregatedSources)
        }
    }
    repositories {
        maven { url = uri(layout.buildDirectory.dir("repo")) }
    }
}
