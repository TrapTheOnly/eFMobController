plugins {
    `java`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val androidJar = file(
    (System.getenv("LOCALAPPDATA") ?: "") + "/Android/Sdk/platforms/android-35/android.jar"
)

dependencies {
    implementation(project(":engine"))
    compileOnly(files(androidJar))
}

tasks.register<Exec>("dex") {
    dependsOn("jar", ":engine:jar")
    val outDir = layout.buildDirectory.dir("dex")
    val d8 = (System.getenv("LOCALAPPDATA") ?: "") + "/Android/Sdk/build-tools/35.0.1/d8.bat"
    val bridgeJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val engineJar = project(":engine").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doFirst {
        outDir.get().asFile.mkdirs()
        commandLine(
            d8,
            "--min-api", "26",
            "--output", outDir.get().asFile.absolutePath,
            bridgeJar.get().asFile.absolutePath,
            engineJar.get().asFile.absolutePath
        )
    }
}

tasks.register<Zip>("bridgeJar") {
    dependsOn("dex")
    archiveFileName.set("padlayer-bridge.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(layout.buildDirectory.dir("dex"))
}

tasks.register<Exec>("nativeLib") {
    val sdk = (System.getenv("LOCALAPPDATA") ?: "") + "/Android/Sdk"
    val clang = sdk + "/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd"
    val out = layout.buildDirectory.file("libpadlayer.so")
    inputs.file("src/main/cpp/grab.c")
    outputs.file(out)
    doFirst {
        out.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "cmd", "/c", clang,
        "-shared", "-fPIC", "-O2",
        "-o", out.get().asFile.absolutePath,
        file("src/main/cpp/grab.c").absolutePath
    )
}
