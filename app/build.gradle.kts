plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.addbanner.clean"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.addbanner.clean"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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

// 产物命名：AddBanner-<buildType>-v-<major-minor-patch>.apk
androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType
        variant.outputs.forEach { output ->
            val version = (output.versionName.orNull ?: "1.0.0").replace('.', '-')
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach
            impl.outputFileName.set("AddBanner-$buildType-v-$version.apk")
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
}
