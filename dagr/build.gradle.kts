
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.remmerw"
version = "0.4.1"

kotlin {


    androidLibrary {
        namespace = "io.github.remmerw.dagr"
        compileSdk = 36
        minSdk = 27



        // Opt-in to enable and configure device-side (instrumented) tests
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }
    }


    jvm()
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()
    // linuxArm64()
    // linuxX64()
    // linuxArm64()
    // wasmJs()
    // wasmWasi()
    // js()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.io.core)
                implementation(libs.ktor.network)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

    }
}



mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "dagr", version.toString())

    pom {
        name = "dagr"
        description = "UDP Client Server"
        inceptionYear = "2025"
        url = "https://github.com/remmerw/dagr/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "remmerw"
                name = "Remmer Wilts"
                url = "https://github.com/remmerw/"
            }
        }
        scm {
            url = "https://github.com/remmerw/dagr/"
            connection = "scm:git:git://github.com/remmerw/dagr.git"
            developerConnection = "scm:git:ssh://git@github.com/remmerw/dagr.git"
        }
    }
}
