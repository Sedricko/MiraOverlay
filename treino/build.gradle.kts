plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sedricko.miratreino"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sedricko.miratreino"
        minSdk = 24
        targetSdk = 34
        // sobe a cada build, senão o Android não deixa instalar por cima
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }
    // chave fixa: toda versão sai assinada igual e instala por cima da anterior
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
