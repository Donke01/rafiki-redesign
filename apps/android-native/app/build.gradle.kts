import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val keyProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "org.rafikioutreach.pin"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.rafikioutreach.pin"
        minSdk = 24
        targetSdk = 36
        versionCode = 51
        versionName = "2.26.0"
        buildConfigField("String", "SERVER", "\"https://rafiki-redesign.pages.dev\"")
    }
    signingConfigs {
        create("release") {
            if (keyProps.getProperty("storeFile") != null) {
                storeFile = file(keyProps.getProperty("storeFile")); storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias"); keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }
    // Two ways to ship the same app (same applicationId, same signing key, so a phone can move from one to the other):
    //  site: the APK on the website (/app/rafiki-pin.apk); updates itself from inside the app. assembleSiteRelease
    //  play: Google Play; no self-update and no REQUEST_INSTALL_PACKAGES, Play's in-app updates instead. bundlePlayRelease
    flavorDimensions += "store"
    productFlavors {
        create("site") { dimension = "store"; buildConfigField("boolean", "PLAY", "false") }
        create("play") { dimension = "store"; buildConfigField("boolean", "PLAY", "true") }
    }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware"); it.maxHeapSize = "2g" } } }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // QR stickers: ZXing makes the codes (and reads a photo of one); Google's code scanner reads them with the
    // camera through Google Play services, so the app needs no camera permission and stays small
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // play flavor only: Google Play's in-app updates (flexible: downloads in the background, then a restart)
    "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")
    // Chat (feat-chata): a WebSocket for live messages, typing and presence; Media3 Transformer shrinks videos to 720p before upload
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // Chat push (chatpa): Firebase Cloud Messaging. Optional: without app/google-services.json the app works as before
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    testImplementation(bom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Chat push (chatpa): app/google-services.json is optional and gitignored (the repo is public). When it is there, its
// values become string resources (fcm_*) that the app reads at start to switch on Firebase Cloud Messaging; when it
// isn't, the build, the tests and the app work exactly as without push. No Google Services plugin needed.
file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val j = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val info = j["project_info"] as? Map<String, Any?> ?: emptyMap()
    val client = (j["client"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { c ->
        ((c["client_info"] as? Map<String, Any?>)?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == "org.rafikioutreach.pin" }
    val appId = (client?.get("client_info") as? Map<String, Any?>)?.get("mobilesdk_app_id")?.toString().orEmpty()
    val key = ((client?.get("api_key") as? List<Map<String, Any?>>)?.firstOrNull()?.get("current_key"))?.toString().orEmpty()
    if (appId.isNotBlank() && key.isNotBlank()) android.defaultConfig {
        resValue("string", "fcm_app_id", appId); resValue("string", "fcm_api_key", key)
        resValue("string", "fcm_project_id", info["project_id"]?.toString().orEmpty()); resValue("string", "fcm_sender_id", info["project_number"]?.toString().orEmpty())
    }
}
