plugins {
    id("com.android.application")
}

android {
    namespace = "com.padlayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.padlayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bridgeAssets"))
}

val stageBridgeAssets = tasks.register<Copy>("stageBridgeAssets") {
    dependsOn(":bridge:bridgeJar", ":bridge:nativeLib")
    from(project(":bridge").layout.buildDirectory.dir("dist")) {
        include("padlayer-bridge.jar")
    }
    from(project(":bridge").layout.buildDirectory.file("libpadlayer.so"))
    into(layout.buildDirectory.dir("generated/bridgeAssets"))
}

tasks.named("preBuild").configure {
    dependsOn(stageBridgeAssets)
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
