pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
val localReaderProperties = java.util.Properties().apply {
    val local = file("local.properties")
    if (local.isFile) local.inputStream().use { load(it) }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories {
    localReaderProperties.getProperty("reader.mavenRepository")?.let { repository -> maven { url = uri(repository) } }
    google(); mavenCentral()
} }
rootProject.name = "ForkReader"
include(":app")
