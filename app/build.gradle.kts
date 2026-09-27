import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.shohei0205.yamamuki"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.shohei0205.yamamuki"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // 配布版の署名鍵。キーストアとパスワードはリポジトリに入れず、PC 内の properties ファイルから読む
    // (既定は ~/.android/yamamuki-release.properties。環境変数 YAMAMUKI_SIGNING_PROPERTIES で変えられる)。
    // ファイルが無ければ署名せずにビルドする。
    val signingProps = (System.getenv("YAMAMUKI_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android/yamamuki-release.properties")
        .let(::file)
        .takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // 配布版と同じ端末に入れられるよう、開発版は別のアプリにする(名前は src/debug の strings.xml)。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

val verifyBundledData by tasks.registering {
    val data = layout.projectDirectory.file("src/main/assets/offline/peaks.zip")
    val hash = layout.projectDirectory.file("src/main/assets/offline/peaks.sha256")
    inputs.files(data, hash)
    doLast {
        check(data.asFile.exists() && hash.asFile.exists()) {
            "内蔵データがありません。tools/offline_data/build-data.ps1 を実行してください。"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        data.asFile.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == hash.asFile.readText().trim()) {
            "内蔵データの SHA-256 が一致しません。再ビルドしてください。"
        }
    }
}
val verifyTerrainData by tasks.registering {
    val pack = layout.projectDirectory.file("src/main/assets/offline/terrain.zip")
    val hash = layout.projectDirectory.file("src/main/assets/offline/terrain.sha256")
    inputs.files(pack, hash)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        pack.asFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == hash.asFile.readText().trim()) {
            "地形パックのSHA-256が一致しません。Git LFSの実データを取得してください。"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledData, verifyTerrainData) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("io.github.shohei0205.yamamuki:core")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
