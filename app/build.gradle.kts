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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
