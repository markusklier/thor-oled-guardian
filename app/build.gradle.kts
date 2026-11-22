plugins {
    id("com.android.application")
}

// Die Action übergibt -PbuildNumber=<Laufnummer>: jede APK lässt sich über die vorige installieren.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "dev.thor.oledguardian"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.thor.oledguardian"
        minSdk = 31
        targetSdk = 33
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"
    }

    signingConfigs {
        create("guardian") {
            // Eigener Schlüssel über Umgebung (Repository-Secrets), sonst der Vorab-Schlüssel im Repository.
            val external = System.getenv("GUARDIAN_KEYSTORE")
            val pass = System.getenv("GUARDIAN_KEYSTORE_PASSWORD") ?: "thorguardian"
            storeFile = if (!external.isNullOrBlank() && file(external).exists()) file(external) else rootProject.file("guardian-dev.keystore")
            storePassword = pass
            keyAlias = System.getenv("GUARDIAN_KEY_ALIAS") ?: "guardian"
            keyPassword = System.getenv("GUARDIAN_KEY_PASSWORD") ?: pass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("guardian")
        }
        debug {
            signingConfig = signingConfigs.getByName("guardian")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
