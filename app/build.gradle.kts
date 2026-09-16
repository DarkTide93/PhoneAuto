plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PbuildNumber=<run number> so every cloud build gets a higher
// versionCode and installs over the previous one as an update.
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.maxjax.automator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maxjax.automator"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Fixed key committed with the project so every cloud build installs over the last one.
    signingConfigs {
        create("shared") {
            storeFile = file("automator.keystore")
            storePassword = "android"
            keyAlias = "automator"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // The parser tests run on the desktop JVM; unstubbed framework calls return
    // defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
