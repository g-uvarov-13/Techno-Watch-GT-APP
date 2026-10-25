import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing material belongs outside the checkout. Only its file path is an environment variable.
val signingPropertiesPath = System.getenv("CHASOVOY_SIGNING_PROPERTIES")
val privateSigningProperties = Properties().apply {
    if (!signingPropertiesPath.isNullOrBlank()) {
        File(signingPropertiesPath).inputStream().use { load(it) }
    }
}
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (releaseRequested && signingPropertiesPath.isNullOrBlank()) {
    throw GradleException("Set CHASOVOY_SIGNING_PROPERTIES to your private signing configuration file.")
}

android {
    namespace = "app.gtdial.lab"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "app.gtdial.lab"
        minSdk = 26
        targetSdk = 36
        versionCode = 104
        versionName = "1.0.2"
    }

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!animdiag-native10:!animdiag-js10"
    }

    signingConfigs {
        if (!signingPropertiesPath.isNullOrBlank()) {
            create("publicRelease") {
                storeFile = file(privateSigningProperties.getProperty("storeFile"))
                storePassword = privateSigningProperties.getProperty("storePassword")
                keyAlias = privateSigningProperties.getProperty("keyAlias")
                keyPassword = privateSigningProperties.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (!signingPropertiesPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("publicRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


