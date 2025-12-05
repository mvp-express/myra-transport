plugins {
    `java-library`
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    checkstyle
}

group = "express.mvp.myra.transport"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    flatDir {
        dirs("libs")
    }
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
// NOTE: SpotBugs is disabled because it does not yet support Java 25 class files (version 69).
// Re-enable when SpotBugs adds support for Java 25+.
spotbugs {
    ignoreFailures.set(true)  // Disabled until Java 25 support is added
    showStackTraces.set(true)
    showProgress.set(true)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
}

// Disable SpotBugs tasks entirely for now
tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    enabled = false  // Disabled until Java 25 support is added
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
    // roray-ffm-utils for FFM utilities (DowncallFactory, LinuxLayouts, etc.)
    implementation(files("libs/roray-ffm-utils-0.1.0-SNAPSHOT.jar"))
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.12.1")
        }
    }
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
    jvmArgs("--enable-preview")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}
