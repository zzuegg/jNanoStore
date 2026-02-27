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

// ---------------------------------------------------------------------------
// Valhalla (JEP 401) source set — compiled and run only when the Valhalla EA
// JDK is supplied via: ./gradlew valhallaTest -PvalhallaJdkPath=/path/to/jdk
// Download the JDK from https://jdk.java.net/valhalla/
// ---------------------------------------------------------------------------
val valhallaJdkPath: String? = findProperty("valhallaJdkPath") as String?

sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
        compileClasspath += sourceSets["main"].output + configurations["jmhCompileClasspath"]
        runtimeClasspath += output + compileClasspath
    }
    if (valhallaJdkPath != null) {
        create("valhallaTest") {
            java.srcDir("src/valhallaTest/java")
            compileClasspath += sourceSets["main"].output
            runtimeClasspath += output + compileClasspath
        }
    }
}

configurations {
    val jmhImplementation by getting {
        extendsFrom(configurations["implementation"])
    }
    val jmhAnnotationProcessor by getting
    if (valhallaJdkPath != null) {
        val valhallaTestImplementation by getting {
            extendsFrom(configurations["testImplementation"])
        }
        val valhallaTestRuntimeOnly by getting {
            extendsFrom(configurations["testRuntimeOnly"])
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}

// ---------------------------------------------------------------------------
// valhallaTest task — compiles and runs ValhallaValueRecordTest using the
// Valhalla EA JDK.  Only registered when -PvalhallaJdkPath is supplied.
// ---------------------------------------------------------------------------
if (valhallaJdkPath != null) {
    val valhallaJavac = "$valhallaJdkPath/bin/javac"
    val valhallaJava  = "$valhallaJdkPath/bin/java"

    tasks.named<JavaCompile>("compileValhallaTestJava") {
        options.compilerArgs.addAll(listOf("--enable-preview", "--release", "26"))
        options.forkOptions.executable = valhallaJavac
        options.isFork = true
    }

    tasks.register<Test>("valhallaTest") {
        description = "Runs ValhallaValueRecordTest using the Valhalla EA JDK (JEP 401)."
        group = "verification"
        useJUnitPlatform()
        testClassesDirs = sourceSets["valhallaTest"].output.classesDirs
        classpath        = sourceSets["valhallaTest"].runtimeClasspath
        executable       = valhallaJava
        jvmArgs("--enable-preview")
        dependsOn("compileValhallaTestJava")
    }
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
        }
    }
}
