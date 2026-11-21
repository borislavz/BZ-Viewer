plugins {
    id("com.android.library")
}

android {
    namespace = "com.borislav007.bzview.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // QR code generation (pairing code shown on the camera phone).
    api("com.google.zxing:core:3.5.3")
}
