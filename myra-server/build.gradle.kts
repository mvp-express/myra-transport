plugins {
    `java-library`
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    checkstyle
    id("com.vanniktech.maven.publish")
}

repositories {
    mavenCentral()
}

// Spotless configuration for Google Java Format
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.32.0").aosp().reflowLongStrings()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val checkstyleDir = file("${rootProject.projectDir}/config/checkstyle")

// Checkstyle configuration - simplified rules for essential code quality
checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configDirectory.set(checkstyleDir)
    configFile = checkstyleDir.resolve("simple_checks.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// SpotBugs configuration
spotbugs {
    toolVersion.set("4.9.8")
    ignoreFailures.set(false)
    showStackTraces.set(true)
    showProgress.set(true)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports {
        create("html") {
            required.set(true)
            outputLocation.set(file("${layout.buildDirectory.get()}/reports/spotbugs/${name}.html"))
        }
        create("xml") {
            required.set(true)
            outputLocation.set(file("${layout.buildDirectory.get()}/reports/spotbugs/${name}.xml"))
        }
    }
}

dependencies {
    implementation(project(":lib"))
    implementation("org.slf4j:slf4j-api:2.0.9")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "myra-transport-server",
        version = version.toString(),
    )

    pom {
        name.set("myra-transport-server")
        description.set("Server runtime for Myra Transport with io_uring support.")
        inceptionYear.set("2025")
        url.set("https://github.com/mvp-express/myra-transport")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("mvp-express")
                name.set("MVP Express Team")
                email.set("hi@mvp.express")
            }
        }

        scm {
            url.set("https://github.com/mvp-express/myra-transport")
            connection.set("scm:git:git://github.com/mvp-express/myra-transport.git")
            developerConnection.set("scm:git:ssh://git@github.com/mvp-express/myra-transport.git")
        }
    }
}
