pluginManagement { repositories { google(); maven("https://repo1.maven.org/maven2"); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); maven("https://repo1.maven.org/maven2"); mavenCentral() } }
rootProject.name = "rafiki-pin-native"
include(":app")
