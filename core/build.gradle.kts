dependencies { "api"("io.github.59alex:file-streams:0.1.0") }

val compileExamples by tasks.registering(JavaCompile::class) {
    source(rootProject.fileTree("examples/java") { include("**/*.java") })
    classpath = sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("example-classes"))
    options.release.set(17)
}
tasks.named("check") { dependsOn(compileExamples) }
