plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.b2y.danmaku"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.b2y.danmaku"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    /**
     * 发布签名。
     *
     * 只在提供了 B2Y_KEYSTORE_PATH 环境变量时才启用 —— 也就是只在 CI 的发版流程里。
     * 本地开发用 assembleDebug 即可，不需要任何密钥。
     *
     * 使用固定的发布密钥很重要：GitHub runner 每次构建都会生成新的 debug 密钥，
     * 导致不同次构建的 APK 签名不一致，用户无法覆盖安装。
     */
    val signingReady = !System.getenv("B2Y_KEYSTORE_PATH").isNullOrBlank()

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = file(System.getenv("B2Y_KEYSTORE_PATH"))
                storePassword = System.getenv("B2Y_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("B2Y_KEY_ALIAS")
                keyPassword = System.getenv("B2Y_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Xposed 模块只需编译期依赖：运行时由 Vector / LSPosed 框架提供
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
