plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "com.roysolberg.java"
version = "2.0.0-SNAPSHOT"
description = "Web server for setting up and sending commands to an HDL Buspro smart home system."

val mainClassName = "com.roysolberg.java.hdlserver.Application"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spark.core)
    implementation(libs.spark.velocity)
    implementation(platform(libs.jetty.bom))
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j.impl)
    implementation(libs.commons.lang)
    implementation(libs.mapdb)
    implementation(libs.json.simple) {
        exclude(group = "junit", module = "junit") // Wrongly declared as a compile dependency
    }
    constraints {
        // Pulled in by Velocity 1.7, which asks for the older 3.2.1
        implementation(libs.commons.collections)
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The build compiles for Java 17; CI also runs the tests on newer Java with -PtestJavaVersion=21 and so on.
val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt).orElse(17)

tasks.test {
    useJUnitPlatform()
    javaLauncher = javaToolchains.launcherFor { languageVersion = testJavaVersion.map(JavaLanguageVersion::of) }
    // ./gradlew test -Dhdlserver.legacyDb=/path/to/hdlserver.db also checks a real database (see misc/legacy-db)
    providers.systemProperty("hdlserver.legacyDb").orNull?.let { systemProperty("hdlserver.legacyDb", it) }
}

tasks.jar {
    manifest.attributes("Main-Class" to mainClassName)
}

// The runnable JAR with all dependencies, released as releases/hdlserver-LATEST.jar
tasks.shadowJar {
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
