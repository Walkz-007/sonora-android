pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// reqwest checks TLS certificates with Android's own verifier, whose Kotlin half ships inside the
// rustls-platform-verifier-android crate as a local Maven repository. CI passes its folder in;
// a local build finds it through `cargo metadata`.
fun rustlsVerifierMaven(): String {
    providers.gradleProperty("rustlsMaven").orNull?.let { return it }
    val metadata = providers.exec {
        workingDir = file("..")
        commandLine(
            "cargo", "metadata", "--format-version", "1",
            "--filter-platform", "aarch64-linux-android",
            "--manifest-path", file("../core/Cargo.toml").path,
        )
    }.standardOutput.asText.get()
    @Suppress("UNCHECKED_CAST")
    val packages = (groovy.json.JsonSlurper().parseText(metadata) as Map<String, Any>)["packages"]
        as List<Map<String, Any>>
    val manifest = packages.first { it["name"] == "rustls-platform-verifier-android" }["manifest_path"]
        as String
    return File(File(manifest).parentFile, "maven").path
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(rustlsVerifierMaven())
            metadataSources.artifact()
        }
    }
}

rootProject.name = "SonoraAndroid"
include(":app")
