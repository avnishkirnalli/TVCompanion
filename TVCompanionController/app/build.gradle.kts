plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.avnishgamedev.tvcompanioncontroller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avnishgamedev.tvcompanioncontroller"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.gson)

    // 1. Cryptography (Bouncy Castle) - CRITICAL for Pairing
    // Original: org.bouncycastle:bcprov-jdk15on:1.70
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    // 2. Protocol Buffers - CRITICAL for Sending Commands
    // Original: com.google.protobuf:protobuf-java:3.11.4
    implementation("com.google.protobuf:protobuf-java:3.11.4")
    // 3. Kotlin Standard Library
    // Original: org.jetbrains.kotlin:kotlin-stdlib-jdk8
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0")
    // 4. Logging (ADAPTED FOR ANDROID)
    // The original pom uses Log4j, which is for servers.
    // On Android, we use SLF4J-Android to make logs appear in Logcat.
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-android:1.7.36")
}