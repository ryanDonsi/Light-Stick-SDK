import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaBasePlugin

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.lightstick"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(libs.versions.jvmTarget.get().toInt())
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":lightstick-internal-core"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// ──────────────────────────────────────────────────────────────
// 1. Sources Jar – archiveFile 자동 생성
// ──────────────────────────────────────────────────────────────
val sourcesJar by tasks.registering(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    archiveClassifier.set("sources")
    archiveFileName.convention("lightstick-${project.version}-sources.jar")
    destinationDirectory.convention(layout.buildDirectory.dir("jars"))

    val main = android.sourceSets.getByName("main")
    from(main.java.srcDirs)
    from(kotlin.sourceSets.getByName("main").kotlin.srcDirs)
}

// ──────────────────────────────────────────────────────────────
// 2. Javadoc Jar
// ──────────────────────────────────────────────────────────────
val dokkaJavadocJar by tasks.registering(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    archiveClassifier.set("javadoc")
    archiveFileName.convention("lightstick-${project.version}-javadoc.jar")
    destinationDirectory.convention(layout.buildDirectory.dir("jars"))

    dependsOn(tasks.named("dokkaJavadoc"))
    from(layout.buildDirectory.dir("dokka/javadoc"))
}

// ──────────────────────────────────────────────────────────────
// 3. Maven Publish
// ──────────────────────────────────────────────────────────────
publishing {
    publications {
        create<MavenPublication>("mavenRelease") {
            afterEvaluate { from(components["release"]) }
            artifact(sourcesJar.get())
            artifact(dokkaJavadocJar.get())

            groupId = "com.lightstick"
            artifactId = "lightstick"
            version = "1.0.0"
        }
    }
    repositories {
        mavenLocal()
    }
}