plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "ua.obdspeed"
 compileSdk = 35
 defaultConfig { applicationId = "ua.obdspeed"; minSdk = 23; targetSdk = 35; versionCode = 3; versionName = "1.2" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
