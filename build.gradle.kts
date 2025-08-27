plugins {
    kotlin("jvm") version "2.2.20-RC"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.github.saintedlittle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }

    maven("https://nexus.phoenixdevt.fr/repository/maven-public/") {
        name = "phoenix"
    }
    maven("https://maven.devs.beer/") {
        name = "matteodev"
    }
    maven("https://repo.extendedclip.com/releases/") {
        name = "placeholdersapi"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("dev.lone:api-itemsadder:4.0.10")
    compileOnly("io.lumine:MythicLib-dist:1.6.2-SNAPSHOT") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.telegram:telegrambots:6.9.7.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21")
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
