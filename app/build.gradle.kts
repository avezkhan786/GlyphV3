import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

val versionPropsFile = rootProject.file("version.properties")

fun getVersionCode(): Int {
    if (versionPropsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(versionPropsFile))
        return props.getProperty("VERSION_CODE").toInt()
    }
    return 1
}

android {
    namespace = "com.glyph.glyph_v3"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glyph.glyph_v3"
        // Increased minSdk to 26 to support adaptive icons and modern APIs
        minSdk = 26 
        targetSdk = 34
        versionCode = getVersionCode()
        versionName = libs.versions.versionName.get()

        buildConfigField("String", "KLIPY_API_KEY", "\"yILOtI5eslMbOr3W6LVKazfkyUOvpCvz8k03fpzJCxAFOk88ZsGUZWMZQ9QcqTaT\"")
        buildConfigField("String", "KLIPY_BASE_URL", "\"https://api.klipy.com\"")

        val localProps = Properties().apply {
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                localPropsFile.inputStream().use { load(it) }
            }
        }

        fun readProperty(name: String): String? {
            return project.findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Google Maps API key – replace with your own key
        val mapsKey = readProperty("MAPS_API_KEY") ?: "YOUR_MAPS_API_KEY"
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
        manifestPlaceholders["mapsApiKey"] = mapsKey

        // TURN server configuration for WebRTC NAT traversal
        // For production, add these to local.properties to override defaults
        // TURN_SERVER_URL1=turn:your-turn-server.com:3478
        // TURN_SERVER_USERNAME1=your-username
        // TURN_SERVER_PASSWORD1=your-password
        // TURN_CREDENTIALS_URL points to a provider endpoint that returns an iceServers JSON array.
        // Metered (recommended primary):
        // METERED_APP_NAME=your-app-name
        // METERED_TURN_API_KEY=your-api-key
        // Derived URL: https://<METERED_APP_NAME>.metered.live/api/v1/turn/credentials?apiKey=<METERED_TURN_API_KEY>
        val meteredAppName = readProperty("METERED_APP_NAME") ?: ""
        val meteredTurnApiKey = readProperty("METERED_TURN_API_KEY") ?: ""
        val derivedMeteredCredentialsUrl = if (meteredAppName.isNotBlank() && meteredTurnApiKey.isNotBlank()) {
            "https://${meteredAppName}.metered.live/api/v1/turn/credentials?apiKey=${meteredTurnApiKey}"
        } else {
            ""
        }

        val turnCredentialsUrl = readProperty("TURN_CREDENTIALS_URL")
            ?: readProperty("METERED_TURN_CREDENTIALS_URL")
            ?: derivedMeteredCredentialsUrl
        val turnCredentialsAuthHeader = readProperty("TURN_CREDENTIALS_AUTH_HEADER") ?: ""
        buildConfigField("String", "TURN_CREDENTIALS_URL", "\"$turnCredentialsUrl\"")
        buildConfigField("String", "TURN_CREDENTIALS_AUTH_HEADER", "\"$turnCredentialsAuthHeader\"")

        // Static Metered TURN fallback (slots 1-5).
        // Add the following to local.properties to activate Metered static relay:
        //   METERED_TURN_USERNAME=<username from dashboard.metered.ca -> TURN Server -> Add Credential>
        //   METERED_TURN_PASSWORD=<password from the same credential>
        // If neither is set the slots are left blank and ONLY the dynamic credential
        // endpoint (TURN_CREDENTIALS_URL / METERED_APP_NAME+METERED_TURN_API_KEY) is used.
        // ExpressTurn has been removed — it is confirmed broken as of 2026-06.
        val meteredTurnUser = readProperty("METERED_TURN_USERNAME") ?: ""
        val meteredTurnPass = readProperty("METERED_TURN_PASSWORD") ?: ""
        val hasMtCreds = meteredTurnUser.isNotBlank() && meteredTurnPass.isNotBlank()

        val turnUrl1 = readProperty("TURN_SERVER_URL1")
            ?: if (hasMtCreds) "turn:global.relay.metered.ca:80" else ""
        val turnUser1 = readProperty("TURN_SERVER_USERNAME1")
            ?: if (hasMtCreds) meteredTurnUser else ""
        val turnPass1 = readProperty("TURN_SERVER_PASSWORD1")
            ?: if (hasMtCreds) meteredTurnPass else ""
        buildConfigField("String", "TURN_SERVER_URL1", "\"$turnUrl1\"")
        buildConfigField("String", "TURN_SERVER_USERNAME1", "\"$turnUser1\"")
        buildConfigField("String", "TURN_SERVER_PASSWORD1", "\"$turnPass1\"")

        val turnUrl2 = readProperty("TURN_SERVER_URL2")
            ?: if (hasMtCreds) "turn:global.relay.metered.ca:80?transport=tcp" else ""
        val turnUser2 = readProperty("TURN_SERVER_USERNAME2") ?: turnUser1
        val turnPass2 = readProperty("TURN_SERVER_PASSWORD2") ?: turnPass1
        buildConfigField("String", "TURN_SERVER_URL2", "\"$turnUrl2\"")
        buildConfigField("String", "TURN_SERVER_USERNAME2", "\"$turnUser2\"")
        buildConfigField("String", "TURN_SERVER_PASSWORD2", "\"$turnPass2\"")

        val turnUrl3 = readProperty("TURN_SERVER_URL3")
            ?: if (hasMtCreds) "turn:global.relay.metered.ca:443" else ""
        val turnUser3 = readProperty("TURN_SERVER_USERNAME3") ?: turnUser1
        val turnPass3 = readProperty("TURN_SERVER_PASSWORD3") ?: turnPass1
        buildConfigField("String", "TURN_SERVER_URL3", "\"$turnUrl3\"")
        buildConfigField("String", "TURN_SERVER_USERNAME3", "\"$turnUser3\"")
        buildConfigField("String", "TURN_SERVER_PASSWORD3", "\"$turnPass3\"")

        val turnUrl4 = readProperty("TURN_SERVER_URL4")
            ?: if (hasMtCreds) "turn:global.relay.metered.ca:443?transport=tcp" else ""
        val turnUser4 = readProperty("TURN_SERVER_USERNAME4") ?: turnUser1
        val turnPass4 = readProperty("TURN_SERVER_PASSWORD4") ?: turnPass1
        buildConfigField("String", "TURN_SERVER_URL4", "\"$turnUrl4\"")
        buildConfigField("String", "TURN_SERVER_USERNAME4", "\"$turnUser4\"")
        buildConfigField("String", "TURN_SERVER_PASSWORD4", "\"$turnPass4\"")

        val turnUrl5 = readProperty("TURN_SERVER_URL5")
            ?: if (hasMtCreds) "turns:global.relay.metered.ca:443?transport=tcp" else ""
        val turnUser5 = readProperty("TURN_SERVER_USERNAME5") ?: turnUser1
        val turnPass5 = readProperty("TURN_SERVER_PASSWORD5") ?: turnPass1
        buildConfigField("String", "TURN_SERVER_URL5", "\"$turnUrl5\"")
        buildConfigField("String", "TURN_SERVER_USERNAME5", "\"$turnUser5\"")
        buildConfigField("String", "TURN_SERVER_PASSWORD5", "\"$turnPass5\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CRITICAL: Enable R8 optimizations for better performance
            isShrinkResources = true
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        // CRITICAL: Enable strong skipping mode for better Compose performance
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-receivers"
        )
    }
    
    // PERFORMANCE: Enable Compose stability inference
    composeCompiler {
        stabilityConfigurationFile = project.layout.projectDirectory.file("stability_config.conf")
    }
    
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/NOTICE",
                "META-INF/LICENSE"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // Standard assets directory is src/main/assets
            // Ensure source dirs are not duplicated by plugins
            java.setSrcDirs(listOf("src/main/java"))
        }
    }

    lint {
        disable.add("NewApi")  // Allow API usage for minSdk compatibility
        abortOnError = false   // Continue build despite lint errors
    }
}

// Ensure Kotlin source set is not duplicated
kotlin {
    sourceSets {
        val main by getting {
            kotlin.setSrcDirs(listOf("src/main/java"))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)
    implementation(libs.gson)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.lottie)
    implementation(libs.lottie.compose)
    // Installs/AOT-compiles the bundled baseline-prof.txt on local installs and API 24-30,
    // so the chat RecyclerView bind/inflate path is pre-compiled (no first-scroll JIT warm-up).
    implementation(libs.androidx.profileinstaller)
    
    // PhotoView for zoomable images
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    
    // uCrop for image cropping
    implementation("com.github.yalantis:ucrop:2.2.8")

    // LocalBroadcastManager — used for in-process notification→activity communication
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Jetpack Compose (Material 3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)
    implementation("com.google.firebase:firebase-functions-ktx")
    implementation(libs.firebase.functions)
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    
    // CameraX for in-app camera
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ExoPlayer for video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // WorkManager for background downloads
    implementation(libs.androidx.work.runtime)

    // Biometric authentication for app lock
    implementation("androidx.biometric:biometric:1.1.0")

    // ViewPager2 for unified picker tabs
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Retrofit + OkHttp for Klipy API
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    // Google Maps SDK + Maps Compose + Fused Location
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("com.google.maps.android:maps-compose-utils:4.3.3")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("io.getstream:stream-webrtc-android-ui:1.3.10")
    // .await() extension for Task<Location> (client.lastLocation.await())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Google Sign-In (for Google account + Drive auth)
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Google Drive REST API (AppDataFolder access)
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register("incrementVersionCode") {
    doLast {
        if (versionPropsFile.exists()) {
            val props = Properties()
            props.load(FileInputStream(versionPropsFile))
            val code = props.getProperty("VERSION_CODE").toInt()
            props.setProperty("VERSION_CODE", (code + 1).toString())
            props.store(FileOutputStream(versionPropsFile), null)
            println("Incremented Version Code to: ${code + 1}")
        }
    }
}

// Hook into release builds to auto-increment version code
tasks.whenTaskAdded {
    if (name == "generateReleaseBuildConfig") {
        dependsOn("incrementVersionCode")
    }
}
