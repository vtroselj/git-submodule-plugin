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

    // Coroutines support
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    intellijPlatform {
        intellijIdeaCommunity("2023.3.2")
        bundledPlugin("Git4Idea")

        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    // Testing dependencies
    testImplementation(kotlin("test"))
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        name = "Git Submodule Plugin"
        description = "Git Submodules Management Plugin for IntelliJ IDEA."
        changeNotes = """
            <h3>Version 1.2</h3>
            <ul>
                <li>Add/Remove Git submodules</li>
                <li>Initialize and update submodules</li>
                <li>Switch submodule branches</li>
                <li>Batch operations support</li>
                <li>Performance caching</li>
                <li>Enhanced tool window with multi-select</li>
            </ul>
        """.trimIndent()

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
            freeCompilerArgs = listOf("-Xjsr305=strict")
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