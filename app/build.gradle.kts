plugins { id("com.android.application") }
android {
    namespace = "com.remotecam.app"
    compileSdk = providers.gradleProperty("androidCompileSdk").orElse("36").get().toInt()
    defaultConfig {
        applicationId = "com.remotecam.app"
        minSdk = 28
        targetSdk = providers.gradleProperty("androidCompileSdk").orElse("36").get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation(project(":camera-core")) }
