plugins {
    // AGP 9 incluye el soporte de Kotlin integrado: no hace falta aplicar el plugin kotlin-android.
    alias(libs.plugins.android.application)
}

// Si el proyecto está dentro de OneDrive, este bloquea los archivos temporales de «build» mientras
// Gradle los regenera y la compilación falla con «Unable to delete directory». En ese caso se
// compila en una carpeta local fuera de OneDrive; en cualquier otra ruta se usa «app/build».
if (rootDir.path.contains("OneDrive", ignoreCase = true)) {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.photocollage-build/${project.name}"))
}

android {
    namespace = "com.abelcarballeira.photocollage"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.abelcarballeira.photocollage"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
}
