    plugins {
        alias(libs.plugins.android.application)
        alias(libs.plugins.kotlin.android)
    }

    android {
        namespace = "com.emilock.app"
        compileSdk {
            version = release(36)



        }

        defaultConfig {
            applicationId = "com.emilock.app"
            minSdk = 28
            targetSdk = 36
            versionCode = 1
            versionName = "1.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        signingConfigs {
            create("release") {
                storeFile = file("C:\\Users\\Dell\\AndroidStudioProjects\\keyforEMIapp")
                storePassword = "sandeep3242"
                keyAlias = "tanwar"
                keyPassword = "sandeep500"
            }
            getByName("debug") {
                storeFile = file("C:\\Users\\Dell\\AndroidStudioProjects\\keyforEMIapp")
                storePassword = "sandeep3242"
                keyAlias = "tanwar"
                keyPassword = "sandeep500"
            }
        }


        buildTypes {
            release {
                isMinifyEnabled = false
                signingConfig = signingConfigs.getByName("release")
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            debug {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
        kotlinOptions {
            jvmTarget = "11"
        }
    }

    dependencies {
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.appcompat)
        implementation(libs.material)
        implementation(libs.androidx.activity)
        implementation(libs.androidx.constraintlayout)
        testImplementation(libs.junit)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.espresso.core)

        implementation("com.squareup.okhttp3:okhttp:4.12.0")

    }