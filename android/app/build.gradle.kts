import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "im.flare.todo"
    // 최신 AndroidX 가 API 37 로 컴파일할 것을 요구한다. 동작 기준(targetSdk)은 Android 16 그대로다.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "im.flare.todo"
        // Android 16 전용. 구버전 에뮬레이터에서 확인할 때만 -PminSdkOverride=34 처럼 낮춘다.
        minSdk = (findProperty("minSdkOverride") as String?)?.toInt() ?: 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://todo.flare.im\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 개인용 앱이라 디버그 키로 서명한다. 스토어 배포 시 별도 키스토어로 교체.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.optIn.addAll(
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.foundation.layout.ExperimentalLayoutApi",
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 이 끌어오는 오래된 fragment 는 Activity Result API(권한 요청·파일 선택)와 충돌한다.
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
