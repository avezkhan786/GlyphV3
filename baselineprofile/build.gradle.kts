plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.glyph.glyph_v3.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The producer targets the app's release (non-debuggable) variant — required
    // so the captured profile reflects the real optimized build.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // NOTE: no buildTypes/beforeVariants here on purpose. The
    // androidx.baselineprofile plugin creates its own benchmark<Variant> and
    // nonMinified<Variant> build types and the variant wiring for them; manually
    // declaring `release {}` / `create("benchmark")` (or filtering variants)
    // breaks its producer/consumer variant matching under AGP 9.1:
    //   "No matching variant of project :baselineprofile was found ...
    //    needed a component for use during 'baselineProfile'".
}

baselineProfile {
    // Generate on the connected physical device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
}
