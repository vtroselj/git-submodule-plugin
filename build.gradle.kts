plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "com.example.submodule.plugin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")

    intellijPlatform {
        intellijIdeaCommunity("2023.3.2")
        bundledPlugin("Git4Idea")

        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    testImplementation(kotlin("test"))
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        name = "Git Submodule Plugin"
        description = "Git Submodules Management Plugin for IntelliJ IDEA."
        changeNotes = "Initial release"

        ideaVersion {
            sinceBuild = "233"
            untilBuild = "251.*"
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    compileJava {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    jvmToolchain(17)
}