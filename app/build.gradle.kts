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
        versionCode = 288
        versionName = "0.220.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
            // v0.27.3：release 瘦身——R8 混淆 + 资源压缩，dex 65MB 压到 20MB 内
            isMinifyEnabled = true
            isShrinkResources = true
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
        // v0.29.0：libmpv 插件化——mpv/FFmpeg 的 .so 移到"播放器引擎插件"（ZIP 包经插件市场下载），
        // 壳子只留 AAR 的 Java 层（dev.jdtech.mpv.MPVLib），APK 瘦 21MB。运行时 MpvPluginLoader 按序 System.load。
        jniLibs {
            excludes += "lib/arm64-v8a/libmpv.so"
            excludes += "lib/arm64-v8a/libavcodec.so"
            excludes += "lib/arm64-v8a/libavformat.so"
            excludes += "lib/arm64-v8a/libavutil.so"
            excludes += "lib/arm64-v8a/libswscale.so"
            excludes += "lib/arm64-v8a/libswresample.so"
            excludes += "lib/arm64-v8a/libavfilter.so"
            excludes += "lib/arm64-v8a/libavdevice.so"
            excludes += "lib/arm64-v8a/libpostproc.so"
            excludes += "lib/arm64-v8a/libplayer.so"
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
            // v0.14.6：PY 规则全量常用依赖（一次配全，避免 ModuleNotFoundError）
            // 注意：PyExecJS 无 Android arm64 版本，已剔除
            install("requests")
            install("beautifulsoup4")
            install("pyquery")
            install("pycryptodome")
            install("lxml")
            install("Pillow")
            install("urllib3")
            install("chardet")
            install("idna")
            install("certifi")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    // v0.30.0：字幕搜索插件接口（实现由插件提供，DexClassLoader 动态加载）
    implementation(project(":subtitle-api"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    // v0.26.0：追剧更新检测（周期任务）+ 下载队列（后台 Worker）
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    // v0.53.0：HomeViewModel（分类状态+缓存在 VM 里，SavedStateHandle 存选中，返回不重建）
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // v0.45.0：pin 1.3.1（PullToRefreshBox 下拉组件从 1.3.0 才有，不赌 BOM 带的版本）
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 网络 / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    // v0.202.0：小说通用正文兜底——Mozilla Readability 的 Kotlin 移植（Apache 2.0），规则提空时从整页提正文
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Java spider 宿主：jar 里 SpiderApi.multiReq 签名引用 gson（dex 里无自带）
    implementation("com.google.code.gson:gson:2.10.1")

    // 网页源嗅探：Service Worker 上下文拦截（androidx.webkit 1.5.0+ 引入 SW shouldInterceptRequest）
    implementation("androidx.webkit:webkit:1.10.0")

    // 图片加载（v0.12.1：海报真实图片）
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 播放器 (Media3 / ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // v0.25.0：HLS/DASH 模块——缺 hls 时播 m3u8 会 ClassNotFoundException 直接闪退（真机实锤）
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // v0.85.0：音乐后台播放——MediaSessionService + MediaController 门面
    implementation("androidx.media3:media3-session:1.4.1")

    // v0.20.0：第二引擎 libmpv（Maven Central 预编译 AAR，内嵌 mpv v0.40.0，
    // minSdk 26 与本项目一致；0.5.1 是最后一个支持 compileSdk<=35 的版本）
    implementation("dev.jdtech.mpv:libmpv:0.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 单元测试：type=4 PHP 接口双试探（MockWebServer + 真实 fixture）
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    // v0.113.0：V8 引擎真机冒烟测试（instrumentation 直跑引擎，绕开 UI）
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    // v0.113.0：JS 规则改用 V8 真 async 引擎（替代 app.cash.quickjs）。
    // 引擎由设备 WebView 提供，isolate 内的 await 是天然事件循环，宿主网络请求不再阻塞 JS 线程。
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    // v0.199.0：阅读式 JS 书源用 Rhino（与阅读同款同步引擎；V8 isolate 只有异步宿主调用，跑不了阅读的同步 java.* 规则）。
    implementation("org.mozilla:rhino:1.7.15")
    // javascriptengine 的异步 API 全部返回 ListenableFuture，显式声明确保编译期可见。
    implementation("com.google.guava:guava:33.2.1-android")
}
