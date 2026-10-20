import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adsbanner.clean"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adsbanner.clean"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.3"

        // 检查更新用的 GitHub 凭据从 local.properties 注入。
        // GitHub 的推送保护不允许有效 token 进入提交历史，但 APK 里需要带上它。
        val secrets = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "GITHUB_TOKEN",
            "\"${secrets.getProperty("github.token", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 便于本地直接产出可安装的 release 包
            signingConfig = signingConfigs.getByName("debug")
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
}

// 产物命名：AdsBanner-<buildType>-v-<major-minor-patch>.apk
androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType
        variant.outputs.forEach { output ->
            val version = (output.versionName.orNull ?: "1.0.0").replace('.', '-')
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach
            impl.outputFileName.set("AdsBanner-$buildType-v-$version.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // 更新说明按 Markdown 渲染
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
}
