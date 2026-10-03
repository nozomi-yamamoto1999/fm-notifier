plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "jp.fmnotifier"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "jp.fmnotifier"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("org.jsoup:jsoup:1.17.2")
}