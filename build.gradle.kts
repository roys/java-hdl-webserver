plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.jte)
}

group = "com.roysolberg.java"
version = "2.0.0"
description = "Web server for setting up and sending commands to an HDL Buspro smart home system."

val mainClassName = "com.roysolberg.java.hdlserver.Application"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

// The main class, Application, is built for Java 8 on its own, so too old a Java gets a clear message instead of a crash
val launcher: SourceSet by sourceSets.creating
tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options") // Java 8 is "obsolete" to newer compilers, which is the point here
}

dependencies {
    implementation(libs.javalin)
    implementation(platform(libs.jetty.bom))
    implementation(platform(libs.jetty.ee10.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jte)
    // Only needed to turn the .kte templates into Kotlin; keeps the Kotlin compiler out of the JAR
    compileOnly(libs.jte.kotlin)
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j2.impl)
    implementation(libs.mapdb)

    testImplementation(launcher.output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The pages in src/main/jte are turned into Kotlin and compiled with the rest, so a mistake in them fails the build
jte {
    generate()
    contentType = gg.jte.ContentType.Html
}
kotlin.sourceSets.main {
    kotlin.srcDir(tasks.generateJte)
}

// The build compiles for Java 17; CI also runs the tests on newer Java with -PtestJavaVersion=21 and so on.
val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt).orElse(17)

tasks.test {
    useJUnitPlatform()
    javaLauncher = javaToolchains.launcherFor { languageVersion = testJavaVersion.map(JavaLanguageVersion::of) }
    // ./gradlew test -Dhdlserver.legacyDb=/path/to/hdlserver.db also checks a real database (see misc/legacy-db)
    providers.systemProperty("hdlserver.legacyDb").orNull?.let { systemProperty("hdlserver.legacyDb", it) }
}

// Only the runnable JAR is built, so build/libs/ never holds a JAR without its dependencies
tasks.jar {
    enabled = false
}

// The runnable JAR with all dependencies, released as releases/hdlserver-LATEST.jar
tasks.shadowJar {
    from(launcher.output)
    archiveClassifier = ""
    mergeServiceFiles()
    manifest.attributes(
        "Main-Class" to mainClassName,
        "Implementation-Version" to project.version,
        "Multi-Release" to "true",
    )
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
