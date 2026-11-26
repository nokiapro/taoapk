import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  id("kotlin-parcelize")
}

android {
  namespace = "chromahub.rhythm.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.rhythm.kmxpqz"
    minSdk = 26
    targetSdk = 36
    versionCode = 554991302
    versionName = "5.5.499.1302"

    buildConfigField("String", "RELEASE_DATE", "\"2026-10-13\"")
    buildConfigField("boolean", "IS_NIGHTLY", "false")
    buildConfigField(
      "String",
      "APPLE_MUSIC_FALLBACK_TOKEN",
      "\"eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzgxMDMyODU1LCJleHAiOjE3ODQwNTY4NTUsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.fiMFcJWkfSlxKP9NVA0UW9CbItD1Rge0SISuepz203XcpU762OqdCpU9M-YkmtKkjRmaIWtjsfGgqZPrlMonpA\""
    )
    buildConfigField("boolean", "ENABLE_YOUTUBE_MUSIC", "true")
    buildConfigField("boolean", "ENABLE_LYRICALLY_API", "true")
    buildConfigField("boolean", "ENABLE_DEEZER", "true")
    buildConfigField("boolean", "ENABLE_LRCLIB", "true")
    buildConfigField("boolean", "ENABLE_BETTERLYRICS", "true")
    buildConfigField("boolean", "ENABLE_SPOTIFY_SEARCH", "true")
    buildConfigField("boolean", "ENABLE_WIKIPEDIA", "true")
    buildConfigField("String", "FLAVOR", "\"fdroid\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
      freeCompilerArgs.addAll(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
      )
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }

  packaging {
    resources {
      merges += "/META-INF/INDEX.LIST"
      merges += "**/io.netty.versions.properties"
    }
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(libs.androidx.compose.ui.unit)
  coreLibraryDesugaring(libs.androidx.desugar.jdk.libs)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.core)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.appcompat)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)

  implementation(libs.androidx.material3.android)
  implementation(libs.androidx.material3.window.size)
  implementation(libs.androidx.window)
  implementation(libs.com.google.android.material)

  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.dash)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.datasource.okhttp)
  implementation(libs.androidx.media3.exoplayer.midi)
  implementation(libs.org.jellyfin.media3.ffmpeg.decoder)

  implementation(libs.androidx.palette.ktx)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)

  implementation(libs.androidx.compose.animation)
  implementation(libs.androidx.compose.animation.graphics)
  implementation(libs.androidx.compose.animation.core)
  implementation(libs.reorderables)

  implementation(libs.androidx.navigation.compose)
  implementation(libs.com.google.accompanist.accompanist.permissions)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.mediarouter)
  implementation(libs.io.coil.kt.coil.compose)

  implementation(libs.net.jthink.jaudiotagger)
  implementation(libs.taglib)

  implementation(libs.com.squareup.retrofit2.retrofit)
  implementation(libs.com.squareup.retrofit2.converter.gson)
  implementation(libs.com.squareup.okhttp3.okhttp)
  implementation(libs.com.squareup.okhttp3.logging.interceptor)
  implementation(libs.com.google.code.gson.gson)

  implementation(libs.org.jetbrains.kotlinx.coroutines.core)
  implementation(libs.org.jetbrains.kotlinx.coroutines.android)
  implementation(libs.androidx.foundation.layout)
  implementation(libs.androidx.work.runtime.ktx)

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.paging)
  ksp(libs.androidx.room.compiler)

  implementation(libs.androidx.paging.runtime.ktx)
  implementation(libs.androidx.paging.compose)
  implementation(libs.androidx.profileinstaller)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core.test)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.runner)
  testImplementation(libs.com.squareup.okhttp3.mockwebserver3)
  testImplementation(libs.org.json)
  testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  // Commented out so debug APK does not install a separate "Leaks" launcher app alongside Rhythm
  // debugImplementation(libs.com.squareup.leakcanary.leakcanary.android)
}

composeCompiler {
  includeComposeMappingFile.set(false)
}

tasks.configureEach {
  if (name.contains("AarMetadata")) {
    enabled = false
  }
}
