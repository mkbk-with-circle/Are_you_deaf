plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.nierduolong.morningbell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nierduolong.morningbell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        // 数据库迁移策略要按 debug/release 区分，需要 BuildConfig.DEBUG
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    lint {
        // 依赖升级需要相机、转码、闹钟逐项真机回归；版本提示由人工升级任务管理，
        // 不让纯“有新版”建议掩盖真正的正确性问题。
        disable += setOf("GradleDependency", "ObsoleteLintCustomCheck")
    }
}

// 百度网盘同步会在 build/.gradle 等目录写入 .baiduyun.uploading.cfg，导致 AAPT 链接失败
tasks.register("cleanBaiduyunSyncArtifacts") {
    doLast {
        val rootDir = project.rootProject.projectDir
        var removed = 0
        rootDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.contains("baiduyun", ignoreCase = true)) {
                if (file.delete()) removed++
            }
        }
        // 部分同步盘会把正在生成的 dex/class 冲突副本命名成 “Foo 2.dex”。它们只可能位于
        // build/intermediates，继续交给 D8 会变成 “Type is defined multiple times”。严格限定
        // 在生成目录和生成后缀内清理，不扫描或改动任何源码/资源文件。
        val generatedRoot = layout.buildDirectory.dir("intermediates").get().asFile
        val conflictCopy = Regex(".+ \\d+\\.(dex|class|jar)$")
        if (generatedRoot.isDirectory) {
            generatedRoot.walkTopDown().forEach { file ->
                if (file.isFile && conflictCopy.matches(file.name) && file.delete()) removed++
            }
        }
        if (removed > 0) {
            logger.lifecycle("已清理 $removed 个同步盘构建冲突文件")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("cleanBaiduyunSyncArtifacts")
}

// Room 生成 Kotlin 而非 Java，避免 KSP 增量复制异常时出现重复的 *_Impl 参与 javac
ksp {
    arg("room.generateKotlin", "true")
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    /** 附近 Log 邀请二维码：只引入纯解码核心，相机预览复用已有 CameraX。 */
    implementation("com.google.zxing:core:3.5.3")

    /** 农历生日 → 当年公历，用于提醒日计算 */
    implementation("cn.6tail:lunar:1.7.4")

    /** Setlog 风格「每日日志」：拍摄 + 每日自动合成 + 后台补合成任务 */
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
