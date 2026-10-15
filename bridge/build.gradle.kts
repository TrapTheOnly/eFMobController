import java.util.Properties

plugins {
    `java`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun sdkRoot(): File {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val props = Properties()
        local.inputStream().use { props.load(it) }
        val dir = props.getProperty("sdk.dir")
        if (!dir.isNullOrBlank()) {
            return file(dir)
        }
    }
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank()) {
        return file(fromEnv)
    }
    val localAppData = System.getenv("LOCALAPPDATA")
    if (!localAppData.isNullOrBlank()) {
        return file(localAppData).resolve("Android/Sdk")
    }
    throw GradleException("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.")
}

fun toolCommand(tool: File): List<String> {
    return if (windows) listOf("cmd", "/c", tool.absolutePath) else listOf(tool.absolutePath)
}

val sdk = sdkRoot()
val androidJar = sdk.resolve("platforms/android-35/android.jar")
val d8 = sdk.resolve("build-tools/35.0.1/" + if (windows) "d8.bat" else "d8")
val ndkHost = if (windows) "windows-x86_64" else "linux-x86_64"
val clang = sdk.resolve(
    "ndk/27.0.12077973/toolchains/llvm/prebuilt/$ndkHost/bin/" +
        if (windows) "aarch64-linux-android26-clang.cmd" else "aarch64-linux-android26-clang"
)

dependencies {
    implementation(project(":engine"))
    compileOnly(files(androidJar))
}

tasks.register<Exec>("dex") {
    dependsOn("jar", ":engine:jar")
    val outDir = layout.buildDirectory.dir("dex")
    val bridgeJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val engineJar = project(":engine").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doFirst {
        if (!d8.exists()) {
            throw GradleException("d8 not found at ${d8.absolutePath}")
        }
        outDir.get().asFile.mkdirs()
        commandLine(
            toolCommand(d8) + listOf(
                "--min-api", "26",
                "--output", outDir.get().asFile.absolutePath,
                bridgeJar.get().asFile.absolutePath,
                engineJar.get().asFile.absolutePath
            )
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
    val out = layout.buildDirectory.file("libpadlayer.so")
    inputs.file("src/main/cpp/grab.c")
    outputs.file(out)
    doFirst {
        if (!clang.exists()) {
            throw GradleException("NDK clang not found at ${clang.absolutePath}")
        }
        out.get().asFile.parentFile.mkdirs()
        commandLine(
            toolCommand(clang) + listOf(
                "-shared", "-fPIC", "-O2",
                "-o", out.get().asFile.absolutePath,
                file("src/main/cpp/grab.c").absolutePath
            )
        )
    }
}
