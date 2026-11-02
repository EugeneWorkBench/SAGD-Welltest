plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sagd.welltest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sagd.welltest"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Run JUnit 5 / kotest tests on the unit-test (JVM) source set.
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// Room exports its schema JSON here (exportSchema = true on AppDatabase) so
// schema changes are reviewable and future migrations can be validated.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (ui layer)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Room (localstore layer)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager (sync layer)
    implementation(libs.androidx.work.runtime.ktx)

    // CameraX + ML Kit barcode (integrations layer)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Play services location (integrations layer)
    implementation(libs.play.services.location)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit testing: kotest-property (PBT) + kotest JUnit5 runner
    testImplementation(libs.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json so the network-layer JSON codec parses for real in JVM
    // unit tests (android.jar only provides throwing stubs). Test scope only.
    testImplementation(libs.org.json)
}
