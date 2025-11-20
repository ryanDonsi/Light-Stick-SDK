import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.register
import org.gradle.api.publish.maven.MavenPublication
import com.android.build.gradle.tasks.BundleAar
import org.gradle.api.plugins.JavaBasePlugin

plugins {
    alias(libs.plugins.android.fusedlibrary)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

androidFusedLibrary {
    namespace = "com.lightstick.sdk"
    minSdk = libs.versions.minSdk.get().toInt()
}

dependencies {
    include(project(":lightstick"))
    include(project(":lightstick-internal-core"))
}

// ──────────────────────────────────────────────────────────────
// 4. Sources Jar – 모든 모듈 소스 취합
// ──────────────────────────────────────────────────────────────
val aggregatedSources by tasks.registering(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    archiveClassifier.set("sources")
    duplicatesStrategy = DuplicatesStrategy.WARN
    archiveFileName.convention("lightstick-sdk-${project.version}-sources.jar")
    destinationDirectory.convention(layout.buildDirectory.dir("jars"))

    val modules = listOf(":lightstick", ":lightstick-internal-core")
    modules.forEach { path ->
        val proj = project(path)
        if (proj.plugins.hasPlugin("com.android.library")) {
            val androidExt = proj.extensions.getByName("android") as com.android.build.gradle.LibraryExtension
            val main = androidExt.sourceSets.getByName("main")
            from(main.java.srcDirs)

            val kotlinExt = proj.extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension::class.java)
            kotlinExt?.let {
                from(it.sourceSets.getByName("main").kotlin.srcDirs)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 5. Javadoc Jar
// ──────────────────────────────────────────────────────────────
val aggregatedJavadoc by tasks.registering(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    archiveClassifier.set("javadoc")
    duplicatesStrategy = DuplicatesStrategy.WARN
    archiveFileName.convention("lightstick-sdk-${project.version}-javadoc.jar")
    destinationDirectory.convention(layout.buildDirectory.dir("jars"))

    val modules = listOf(":lightstick", ":lightstick-internal-core")
    modules.forEach { path ->
        val proj = project(path)
        val dokkaTask = proj.tasks.findByName("dokkaJavadoc") ?: proj.tasks.register("dokkaJavadoc").get()
        dependsOn(dokkaTask)
        from(proj.layout.buildDirectory.dir("dokka/javadoc"))
    }
}

// ──────────────────────────────────────────────────────────────
// 6. AAR 이름
// ──────────────────────────────────────────────────────────────
tasks.withType<BundleAar>().configureEach {
    archiveFileName.set("lightstick-sdk-${project.version}.aar")
}

// ──────────────────────────────────────────────────────────────
// 7. Maven Publish
// ──────────────────────────────────────────────────────────────
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.lightstick"
            artifactId = "lightstick-sdk"
            version = "1.4.0"

            from(components["fusedLibraryComponent"])
            artifact(aggregatedSources.get())
            artifact(aggregatedJavadoc.get())

            pom {
                name.set("LightStick SDK (Fused)")
                description.set("All-in-one AAR with public API and internal core.")
                url.set("https://example.com/lightstick-sdk")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
    repositories {
        maven { url = uri(layout.buildDirectory.dir("repo")) }
    }
}