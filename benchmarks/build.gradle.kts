plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

group = "express.mvp.myra.transport"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":lib"))
    implementation(project(":myra-server"))
    implementation("express.mvp.roray.utils.memory:lib:0.1.0-SNAPSHOT")
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    jmh(project(":lib"))
    jmh(project(":myra-server"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmh("express.mvp.roray.utils.memory:lib:0.1.0-SNAPSHOT")
    
    // Netty for comparison benchmarks
    jmh("io.netty:netty-all:4.1.115.Final")
}

jmh {
    warmupIterations.set(2)
    iterations.set(5)
    fork.set(1)
    failOnError.set(true)
    resultFormat.set("JSON")
    
    // Allow filtering benchmarks via -Pbench=<regex> on command line
    val benchFilter = project.findProperty("bench")?.toString()
    if (benchFilter != null) {
        includes.set(listOf(".*$benchFilter.*"))
    }
}
