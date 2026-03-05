plugins {
    `java-library`
}

group = "io.github.zzuegg"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
        compileClasspath += sourceSets["main"].output + configurations["jmhCompileClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    val jmhImplementation by getting {
        extendsFrom(configurations["implementation"])
    }
    val jmhAnnotationProcessor by getting
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.zayes)
    implementation(libs.bytebuddy)

    testImplementation(libs.zayes)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.annprocess)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("jmhRun") {
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("-wi", "3", "-i", "5", "-f", "1", "-bm", "avgt", "-tu", "us")
}

// Lightweight JMH run for CI: 2 warmup, 3 measurement, single fork
tasks.register<JavaExec>("jmhCi") {
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("-wi", "2", "-i", "3", "-f", "1", "-bm", "avgt", "-tu", "us")
}
