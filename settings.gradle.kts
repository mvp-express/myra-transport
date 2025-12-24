plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "roray-myra-transport"
include("lib")
include("benchmarks")
include("myra-server")
