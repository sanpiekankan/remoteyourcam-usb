plugins { id("com.android.library") }
android {
    namespace = "com.remoteyourcam.usb"
    compileSdk = providers.gradleProperty("androidCompileSdk").orElse("36").get().toInt()
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
