plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.zzuegg"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val jmhVersion = "1.37"

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
    implementation("net.bytebuddy:byte-buddy:1.17.5")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("jmhRun") {
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("-wi", "3", "-i", "5", "-f", "1", "-bm", "avgt", "-tu", "ns")
}

// Lightweight JMH run for CI: single warmup, single measurement, single fork
tasks.register<JavaExec>("jmhCi") {
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("-wi", "1", "-i", "1", "-f", "1", "-bm", "avgt", "-tu", "ns")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("BitKit")
                description.set("Type-safe, high-performance, memory-efficient bit-packed datastores for Java")
                url.set("https://github.com/zzuegg/BitKit")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/zzuegg/BitKit")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
