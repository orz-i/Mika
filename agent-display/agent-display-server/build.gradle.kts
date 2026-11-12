import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.agent.display.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agent.display.server"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":agent-display-common"))
}

// 独立构建 agent-display-server.jar (内含 classes.dex) 的打包任务
tasks.register("packageServerJar") {
    group = "build"
    description = "Assembles standalone agent-display-server.jar containing classes.dex for app_process/Shizuku"
    dependsOn("assembleRelease")

    doLast {
        val buildDir = layout.buildDirectory.asFile.get()
        val apkFile = File(buildDir, "outputs/apk/release/agent-display-server-release-unsigned.apk")
            .takeIf { it.exists() }
            ?: File(buildDir, "outputs/apk/release/agent-display-server-release.apk")

        if (apkFile != null && apkFile.exists()) {
            val outputJar = File(buildDir, "outputs/agent-display-server.jar")
            outputJar.parentFile.mkdirs()

            // 从 APK 中提取 classes.dex 并打包为 agent-display-server.jar
            ZipFile(apkFile).use { zip ->
                val dexEntry = zip.getEntry("classes.dex")
                if (dexEntry != null) {
                    ZipOutputStream(outputJar.outputStream()).use { outZip ->
                        outZip.putNextEntry(ZipEntry("classes.dex"))
                        zip.getInputStream(dexEntry).copyTo(outZip)
                        outZip.closeEntry()
                    }
                    println("Successfully created standalone server jar: ${outputJar.absolutePath} (${outputJar.length()} bytes)")
                } else {
                    println("Warning: classes.dex not found in ${apkFile.name}")
                }
            }
        }
    }
}
