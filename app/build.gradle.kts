import java.util.Properties

/**
 * Secrets de signature, cherchés d'abord dans `keystore.properties` — fichier local
 * jamais versionné — puis dans l'environnement, voie qu'emprunte l'intégration
 * continue où les valeurs viennent des secrets du dépôt.
 *
 * Aucun mot de passe n'apparaît donc dans le code ni dans l'historique git.
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
    versionCode = 27
    versionName = "0.11.9"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      // Secrets de signature : d'abord keystore.properties, un fichier local jamais
      // versionné, puis l'environnement — c'est cette seconde voie qu'emprunte
      // l'intégration continue, où les valeurs viennent des secrets du dépôt.
      // Aucun mot de passe n'apparaît donc dans le code ni dans l'historique git.
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
      // Un applicationId distinct de la release : les deux s'installent alors côte
      // à côte sur le même appareil au lieu de s'écraser l'une l'autre, et le
      // suffixe de version évite de confondre les deux dans l'écran « À propos ».
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"

      // Le trousseau de debug n'est pas versionné : sur un clone frais il est absent.
      // On ne l'impose donc que s'il est là, sinon AGP applique sa clé de debug par
      // défaut — sans quoi `assembleDebug` échouerait sur toute nouvelle machine.
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
 * Où Room dépose le schéma de chaque version de la base.
 *
 * Exigé dès lors que `AppDatabase` déclare `exportSchema = true` : sans ce chemin, la
 * compilation échoue. Ces fichiers rendent le schéma inspectable et vérifiable, au
 * lieu de n'exister que dans le code généré.
 */
ksp {
  arg("room.schemaLocation", "${projectDir}/schemas")
}

// Les dépendances mises en commentaire ont été écartées sans être supprimées : les
// remettre ne coûte alors qu'un décommentage.
//
// Retrofit, OkHttp, Moshi, WorkManager et les bibliothèques d'identifiants Google
// ont en revanche été retirées pour de bon : vestiges d'une sauvegarde vers Drive
// abandonnée, aucune ligne de code ne les appelait, et elles voyageaient malgré
// tout dans chaque APK installé.
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
