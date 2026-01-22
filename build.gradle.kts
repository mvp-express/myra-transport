plugins {
    base
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}

// Configure Javadoc for each subproject
subprojects {
    group = "express.mvp.myra.transport"
    version = "0.2.0"

    plugins.withType<JavaPlugin> {
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                encoding = "UTF-8"
                addBooleanOption("-enable-preview", true)
                addStringOption("-release", "25")
                links("https://docs.oracle.com/en/java/javase/25/docs/api/")
                addBooleanOption("Xdoclint:none", true)
                header = "<b>Myra Transport</b>"
                bottom = "Copyright &#169; 2025-2026 MVP.Express. All rights reserved."
            }
        }
    }
}

// Aggregate Javadoc task collects from subprojects after they build
tasks.register("aggregateJavadoc") {
    group = "documentation"
    description = "Generate aggregated Javadoc for all modules"
    
    // Depend on all subproject javadoc tasks
    dependsOn(subprojects.mapNotNull { subproject ->
        subproject.tasks.findByName("javadoc")
    })
    
    doLast {
        val outputDir = layout.buildDirectory.dir("docs/javadoc").get().asFile
        outputDir.mkdirs()
        
        // Copy all subproject javadocs
        subprojects.forEach { subproject ->
            val javadocDir = subproject.layout.buildDirectory.dir("docs/javadoc").get().asFile
            if (javadocDir.exists()) {
                copy {
                    from(javadocDir)
                    into(outputDir.resolve(subproject.name))
                }
            }
        }
        
        // Generate index page
        val indexFile = outputDir.resolve("index.html")
        indexFile.writeText("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Myra Transport API Documentation</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 40px; }
                    h1 { color: #1a202c; }
                    ul { list-style: none; padding: 0; }
                    li { margin: 10px 0; }
                    a { color: #3182ce; text-decoration: none; font-size: 18px; }
                    a:hover { text-decoration: underline; }
                </style>
            </head>
            <body>
                <h1>Myra Transport API Documentation</h1>
                <ul>
                    ${subprojects.filter { it.layout.buildDirectory.dir("docs/javadoc").get().asFile.exists() }
                        .joinToString("\n") { "<li><a href=\"${it.name}/index.html\">${it.name}</a></li>" }}
                </ul>
            </body>
            </html>
        """.trimIndent())
        
        println("Aggregated Javadoc generated at: $outputDir")
    }
}
