plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

android {
    namespace = "com.movieshell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.movieshell"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "0.11.7"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Chaquopy：只打 arm64-v8a（项目 ABI 基线），省体积
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        // Python 版本：3.10（Chaquopy 17 默认值，hipy 生态兼容性最好）
        version = "3.10"
        // 构建机 Python：CI 里用 actions/setup-python 装 3.10，PATH 里有 python3.10
        buildPython("python3.10")
        pip {
            // 纯 Python 包，零原生风险；lxml/Crypto 走 base/spider.py 里的可选 import
            install("requests")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 网络 / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Java spider 宿主：jar 里 SpiderApi.multiReq 签名引用 gson（dex 里无自带）
    implementation("com.google.code.gson:gson:2.10.1")

    // 网页源嗅探：Service Worker 上下文拦截（androidx.webkit 1.5.0+ 引入 SW shouldInterceptRequest）
    implementation("androidx.webkit:webkit:1.10.0")

    // 播放器 (Media3 / ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 单元测试：type=4 PHP 接口双试探（MockWebServer + 真实 fixture）
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
