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
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    jmh(project(":lib"))
    jmh(project(":myra-server"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    
    // Netty for comparison benchmarks
    jmh("io.netty:netty-all:4.1.115.Final")
}

sourceSets {
    named("jmh") {
        java {
            // These benchmarks depend on an external roray utils artifact not present in this workspace.
            // Exclude them so transport benchmarks remain runnable.
            exclude("express/mvp/myra/transport/benchmark/DowncallFactoryBenchmark.java")
            exclude("express/mvp/myra/transport/benchmark/StructAccessorBenchmark.java")
        }
    }
}

jmh {
    val quick = project.findProperty("quick")?.toString()?.toBoolean() == true

    warmupIterations.set(if (quick) 1 else 2)
    iterations.set(if (quick) 1 else 5)
    fork.set(1)
    failOnError.set(true)
    resultFormat.set("JSON")

    // Match historical runs and avoid FFM restricted-method warnings affecting benchmark stability.
    jvmArgsAppend.set(listOf("--enable-native-access=ALL-UNNAMED"))

    // Allow targeting parameter grids via -Pimpl=MYRA or -Pimpl=MYRA,MYRA_SQPOLL.
    // This avoids running NIO/NETTY when we only want Myra directional feedback.
    val implFilter = project.findProperty("impl")?.toString()
    if (!implFilter.isNullOrBlank()) {
        val values = implFilter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isNotEmpty()) {
            benchmarkParameters.put(
                "implementation",
                project.objects.listProperty(String::class.java).value(values)
            )
        }
    }
    
    // Allow filtering benchmarks via -Pbench=<regex> on command line
    val benchFilter = project.findProperty("bench")?.toString()
    if (benchFilter != null) {
        includes.set(listOf(".*$benchFilter.*"))
    }
}
