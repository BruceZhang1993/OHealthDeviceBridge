plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.brucezhang1993.ohealthdevicebridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.brucezhang1993.ohealthdevicebridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}

dependencies {
    implementation("com.highcapable.yukihookapi:api:1.3.2")
    compileOnly("de.robv.android.xposed:api:82")
    ksp("com.highcapable.yukihookapi:ksp-xposed:1.3.2")

    testImplementation("junit:junit:4.13.2")
}

// YukiHookAPI 1.3.x currently writes xposed_init from KSP into the source tree.
// Force AGP merge tasks to run after KSP so clean CI builds always package the entry files.
afterEvaluate {
    listOf("Debug", "Release").forEach { variant ->
        tasks.findByName("merge${variant}Assets")?.dependsOn("ksp${variant}Kotlin")
        tasks.findByName("merge${variant}JavaResource")?.dependsOn("ksp${variant}Kotlin")
    }
}
