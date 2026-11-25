pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Mika"
include(":core", ":app")

include(":aau-common")
project(":aau-common").projectDir = file("aau/aau-common")

include(":aau-server")
project(":aau-server").projectDir = file("aau/aau-server")

include(":aau-client")
project(":aau-client").projectDir = file("aau/aau-client")

include(":aau-a11y")
project(":aau-a11y").projectDir = file("aau/aau-a11y")

// 配对、ADB 协议与助手交接的纯 JVM 内核:aau-setup 的 Android 壳与 :core 共用同一份实现。
include(":aau-shell")
project(":aau-shell").projectDir = file("aau/aau-shell")

include(":aau-ui")
project(":aau-ui").projectDir = file("aau/aau-ui")

include(":aau-setup")
project(":aau-setup").projectDir = file("aau/aau-setup")

include(":aau-remote")
project(":aau-remote").projectDir = file("aau/aau-remote")

// LiteRT 的 Java 边界与端上实体识别:和视觉后端开关无关,一直进构建。
include(":aau-litert")
project(":aau-litert").projectDir = file("aau/aau-litert")

include(":aau-ner")
project(":aau-ner").projectDir = file("aau/aau-ner")

val aauVisionBackend = providers.gradleProperty("aauVisionBackend").orElse("off").get()
require(aauVisionBackend in setOf("off", "litert", "mnn")) { "aauVisionBackend must be off, litert or mnn" }
if (aauVisionBackend != "off") {
    include(":aau-vision")
    project(":aau-vision").projectDir = file("aau/aau-vision")
}
