pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
rootProject.name = "runtime-native-recovery-jvm"
include(":common", ":jar-parser", ":trace-to-bytecode", ":class-rebuilder")
