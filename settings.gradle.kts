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

include(":agent-display-common")
project(":agent-display-common").projectDir = file("agent-display/agent-display-common")

include(":agent-display-server")
project(":agent-display-server").projectDir = file("agent-display/agent-display-server")

include(":agent-display-client")
project(":agent-display-client").projectDir = file("agent-display/agent-display-client")

include(":agent-display-a11y")
project(":agent-display-a11y").projectDir = file("agent-display/agent-display-a11y")

include(":agent-display-setup")
project(":agent-display-setup").projectDir = file("agent-display/agent-display-setup")


