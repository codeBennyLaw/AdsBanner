plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val diagnosticBuild = providers.gradleProperty("diagnosticBuild").orElse("").get()
require(diagnosticBuild.matches(Regex("[A-Za-z0-9._-]{0,64}")))

android {
    namespace = "com.adsbanner.clean"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adsbanner.clean"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "3.1.7"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "DIAGNOSTIC_BUILD", "\"$diagnosticBuild\"")
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

// 诊断产物追加构建标识，避免同版本普通包与诊断包重名。
androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType
        variant.outputs.forEach { output ->
            val version = (output.versionName.orNull ?: "1.0.0").replace('.', '-')
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach
            val suffix = if (buildType == "debug" && diagnosticBuild.isNotEmpty()) "-$diagnosticBuild" else ""
            impl.outputFileName.set("AdsBanner-$buildType-v-$version$suffix.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.window:window:1.5.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // 更新说明按 Markdown 渲染
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
}
