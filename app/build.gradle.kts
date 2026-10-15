import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val demoLocal = Properties().apply {
    val source = rootProject.file("demo.local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}

android {
    namespace = "com.example.prototipopagosoffline"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.prototipopagosoffline"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        val apiBaseUrl = providers.environmentVariable("API_BASE_URL")
            .orElse(providers.gradleProperty("API_BASE_URL"))
            .orElse(demoLocal.getProperty("API_BASE_URL", "https://example.invalid/"))
            .get()
            .trim()
        require(apiBaseUrl.startsWith("https://") && apiBaseUrl.endsWith("/")) {
            "API_BASE_URL debe ser una URL HTTPS terminada en /"
        }
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val mercadoPagoEnabled = providers.environmentVariable("MERCADO_PAGO_ENABLED")
            .orElse(providers.gradleProperty("MERCADO_PAGO_ENABLED"))
            .orElse("false")
            .get()
            .toBooleanStrictOrNull() ?: false
        buildConfigField("boolean", "MERCADO_PAGO_ENABLED", mercadoPagoEnabled.toString())
        fun configuredString(name: String): String = providers.environmentVariable(name)
            .orElse(providers.gradleProperty(name)).orElse(demoLocal.getProperty(name, "")).get()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "ISSUER_PUBLIC_KEY_B64", "\"${configuredString("ISSUER_PUBLIC_KEY_B64")}\"")
        buildConfigField("String", "ISSUER_KEY_ID", "\"${configuredString("ISSUER_KEY_ID")}\"")
        buildConfigField("String", "ISSUER_TRUST_JSON", "\"${configuredString("ISSUER_TRUST_JSON")}\"")
    }

    buildTypes {
        getByName("debug") {
            if (providers.environmentVariable("DEMO_ISOLATED_INSTALL")
                .orElse(providers.gradleProperty("DEMO_ISOLATED_INSTALL"))
                .orElse(demoLocal.getProperty("DEMO_ISOLATED_INSTALL", "false")).get().toBoolean()) {
                applicationIdSuffix = ".manual"
                manifestPlaceholders["appLabel"] = "Pagos Offline Demo"
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.zxing.android)
    implementation(libs.androidx.biometric)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
