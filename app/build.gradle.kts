import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

fun prop(name: String, default: String = ""): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name, default)

val defaultElevenLabsVoiceId = "nPczCjzI2devNBz1zQrb"

fun elevenLabsApiKey(): String =
    prop("ELEVENLABS_API_KEY")
        .ifBlank { prop("My-English-Coach-Key") }
        .ifBlank { prop("MY_ENGLISH_COACH_KEY") }
        .ifBlank { System.getenv("ELEVENLABS_API_KEY") ?: "" }

android {
    signingConfigs {
        create("release") {
            val keystorePath = prop("KEYSTORE_FILE", "elias-release-key.jks")
            val keystoreFile = rootProject.file(keystorePath)
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = prop("KEYSTORE_PASSWORD")
                keyAlias = prop("KEY_ALIAS", "elias-key")
                keyPassword = prop("KEY_PASSWORD")
            }
        }
    }
    namespace = "com.roberto.eliasaitutor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.roberto.eliasaitutor"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLAUDE_API_KEY", "\"${prop("CLAUDE_API_KEY")}\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${prop("DEEPSEEK_API_KEY")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${prop("OPENAI_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${elevenLabsApiKey()}\"")
        buildConfigField(
            "String",
            "ELEVENLABS_VOICE_ID",
            "\"${prop("ELEVENLABS_VOICE_ID", defaultElevenLabsVoiceId)}\""
        )
        buildConfigField("String", "SUPABASE_URL", "\"${prop("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${prop("SUPABASE_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${prop("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${prop("GROQ_API_KEY")}\"")
        buildConfigField("String", "CARTESIA_API_KEY", "\"${prop("CARTESIA_API_KEY")}\"")
        buildConfigField(
            "String",
            "BACKEND_URL",
            "\"${prop("BACKEND_URL", "http://10.0.2.2:3000")}\""
        )

        // Licenciamento comercial Tadeu Apps. Todas são configurações públicas.
        buildConfigField(
            "String",
            "TADEU_APPS_URL",
            "\"${prop("TADEU_APPS_URL", "https://tadeu-apps-core-test2.vercel.app")}\""
        )
        buildConfigField("String", "TADEU_APPS_SUPABASE_URL", "\"${prop("TADEU_APPS_SUPABASE_URL")}\"")
        buildConfigField("String", "TADEU_APPS_SUPABASE_ANON_KEY", "\"${prop("TADEU_APPS_SUPABASE_ANON_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseKeystore = rootProject.file(prop("KEYSTORE_FILE", "elias-release-key.jks"))
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.socket:socket.io-client:2.1.1")

    implementation("io.github.jan-tennert.supabase:supabase-kt:2.5.0")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.5.0")
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.5.0")
    implementation("io.ktor:ktor-client-android:2.3.11")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // RNNoise (Noise Suppression)
//    implementation("com.github.wiryls:rnnoise-android:1.0.1")
}
