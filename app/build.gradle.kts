plugins {
    id("com.android.application")
}

android {
    namespace = "com.jason4zh.gridlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jason4zh.gridlock"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            // 用 debug 签名，方便直接安装测试
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 故意零依赖：只用 framework API，构建快、不易出错
}