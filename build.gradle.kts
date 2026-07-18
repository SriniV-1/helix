plugins {
    java
    application
    id("me.champeau.jmh") version "0.7.2"
}

group = "dev.srini.helix"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.agrona:agrona:1.22.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.release = 21
}

application {
    mainClass = "dev.srini.helix.engine.Replay"
}

tasks.test {
    useJUnitPlatform()
}

// Run the local demo server: the real Java engine behind an HTTP/JSON API, with
// the demo page served from it so Buy/Sell drives the actual MatchingEngine.
tasks.register<JavaExec>("demoServer") {
    group = "application"
    description = "Run the Helix demo server on http://localhost:8080"
    mainClass = "dev.srini.helix.engine.DemoServer"
    classpath = sourceSets["main"].runtimeClasspath
}
