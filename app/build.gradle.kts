import java.util.Properties

/**
 * Signing secrets, looked up first in `keystore.properties` — a local file
 * never versioned — then in the environment, the route taken by continuous
 * integration, where the values come from the repository's secrets.
 *
 * No password therefore appears in the code or in the git history.
 */
val keystoreProperties = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSecret(propertyName: String, environmentName: String): String? =
  keystoreProperties.getProperty(propertyName) ?: System.getenv(environmentName)

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.toche.mesparcours"
    minSdk = 24
    targetSdk = 36
    versionCode = 43
    versionName = "2.1.0"

    // Package name that an update APK must carry before being presented to
    // the installer (see UpdateDownloader.isExpectedApk). The release's, including
    // in the debug build: it is always the release that gets published, and
    // `context.packageName` there would be the `.debug`-suffixed name.
    buildConfigField("String", "UPDATE_PACKAGE_NAME", "\"$applicationId\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      // Signing secrets: first keystore.properties, a local file never
      // versioned, then the environment — this second route is the one taken by
      // continuous integration, where the values come from the repository's secrets.
      // No password therefore appears in the code or in the git history.
      storeFile = file(signingSecret("storeFile", "KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
      storePassword = signingSecret("storePassword", "STORE_PASSWORD")
      keyAlias = signingSecret("keyAlias", "KEY_ALIAS") ?: "upload"
      keyPassword = signingSecret("keyPassword", "KEY_PASSWORD")
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
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // An applicationId distinct from the release's: the two then install side
      // by side on the same device instead of overwriting each other, and the
      // version suffix avoids confusing the two in the “About” screen.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"

      // The debug keystore is not versioned: on a fresh clone it is absent.
      // We therefore only impose it if it is there, otherwise AGP applies its default debug key
      // — without which `assembleDebug` would fail on any new machine.
      if (rootProject.file("debug.keystore").exists()) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

/**
 * Where Room drops the schema of each version of the database.
 *
 * Required as soon as `AppDatabase` declares `exportSchema = true`: without this path,
 * compilation fails. These files make the schema inspectable and verifiable, instead
 * of existing only in generated code.
 */
ksp {
  arg("room.schemaLocation", "${projectDir}/schemas")
}

// Dependencies put in comments were set aside without being deleted: putting
// them back costs only an uncomment.
//
// Retrofit, OkHttp, Moshi and the Google credentials libraries, on the other hand,
// were removed for good: remnants of an abandoned backup to Drive, no line
// of code called them, and they nevertheless traveled in every installed
// APK.
//
// WorkManager had suffered the same fate, for the same reason — then came back with
// the daily update check (UpdateCheckWorker), which needs
// a periodic task that survives the app restarting and is battery-friendly,
// exactly what WorkManager knows how to do.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.play.services.location)
  implementation(libs.osmdroid.android)
  implementation(libs.androidx.work.runtime.ktx)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
