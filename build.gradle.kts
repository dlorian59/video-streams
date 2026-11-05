plugins { base }
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    group = "io.github.59alex"
    version = "0.1.0"
    repositories {
        maven { url = uri(rootProject.file("../file-streams/build/local-repository")) }
        mavenCentral()
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(
            providers.gradleProperty("testJavaVersion").getOrElse(JavaVersion.current().majorVersion).toInt()))
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); maxHeapSize = "256m" }
    tasks.withType<Jar>().configureEach { from(rootProject.file("LICENSE")) { into("META-INF") } }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    extensions.configure<PublishingExtension> {
        repositories { maven { name = "local"; url = uri(rootProject.layout.buildDirectory.dir("local-repository")) } }
        publications {
            create<MavenPublication>("library") {
                from(components["java"])
                artifactId = if (project.name == "core") "video-streams" else "video-streams-${project.name}"
                pom {
                    name.set("Video Streams ${project.name}")
                    description.set("Bounded FFmpeg preparation and authorized file-streams delivery of immutable HLS VOD")
                    url.set("https://github.com/dlorian59/video-streams")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit") } }
                    developers { developer { id.set("dlorian59"); name.set("Aleksandr Kashevarov") } }
                    scm { url.set("https://github.com/dlorian59/video-streams"); connection.set("scm:git:https://github.com/dlorian59/video-streams.git") }
                }
            }
        }
    }
}
