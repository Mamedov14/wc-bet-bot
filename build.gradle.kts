plugins {
    id("application")
    kotlin("jvm") version "1.9.25"
    id("com.google.devtools.ksp") version "1.9.25-1.0.20"
}

group = "wcbet"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain { languageVersion.set(JavaLanguageVersion.of("17")) }
    sourceSets.main { kotlin.srcDir("build/generated/ksp/main/kotlin") }
    sourceSets.test { kotlin.srcDir("build/generated/ksp/test/kotlin") }
}

val koraBom: Configuration by configurations.creating
configurations {
    ksp.get().extendsFrom(koraBom)
    compileOnly.get().extendsFrom(koraBom)
    api.get().extendsFrom(koraBom)
    implementation.get().extendsFrom(koraBom)
}

dependencies {
    koraBom(platform("ru.tinkoff.kora:kora-parent:1.2.16"))
    ksp("ru.tinkoff.kora:symbol-processors")

    implementation("ru.tinkoff.kora:config-hocon")
    implementation("ru.tinkoff.kora:json-module")
    implementation("ru.tinkoff.kora:http-client-ok")
    implementation("ru.tinkoff.kora:database-jdbc")
    implementation("ru.tinkoff.kora:database-flyway")
    implementation("ru.tinkoff.kora:scheduling-jdk")
    implementation("ru.tinkoff.kora:logging-logback")

    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.telegram:telegrambots:6.8.0")

    testImplementation(kotlin("test"))
}

application {
    applicationName = "wc-bet-bot"
    mainClass.set("wcbet.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}

tasks.distTar {
    archiveFileName.set("application.tar")
}
