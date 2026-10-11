plugins {
  id("com.android.application")
}

android {
  namespace = "com.galy.starvios"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.galy.starvios"
    minSdk = 29
    targetSdk = 35
    versionCode = 3
    versionName = "1.2"
  }

  signingConfigs {
    create("fixed") {
      storeFile = file("starvios-debug.jks")
      storePassword = "starvios"
      keyAlias = "starvios"
      keyPassword = "starvios"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("fixed")
    }
    debug {
      signingConfig = signingConfigs.getByName("fixed")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("androidx.core:core-ktx:1.16.0")
  val camerax = "1.5.3"
  implementation("androidx.camera:camera-core:$camerax")
  implementation("androidx.camera:camera-camera2:$camerax")
  implementation("androidx.camera:camera-lifecycle:$camerax")
  implementation("androidx.camera:camera-view:$camerax")
}
