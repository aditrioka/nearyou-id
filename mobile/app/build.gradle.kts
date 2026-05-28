import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Required per Moko Resources docs for multi-module iOS framework setup:
    // "You should enable moko-resources gradle plugin in `resources` module,
    // that contains resources, AND in `shared` module, that compiles into
    // framework for iOS."
    // https://github.com/icerockdev/moko-resources#multi-module-projects
    alias(libs.plugins.mokoResources)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
            implementation(libs.voyager.navigator)
            implementation(libs.voyager.koin)
            implementation(projects.shared.resources)
            implementation(libs.moko.resources)
            implementation(libs.moko.resources.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Moko Resources iOS framework bundling — the plugin needs to know a unique
// package for its generated MR class on this module. We don't ship any actual
// resources from :mobile:app (all live in :shared:resources); the empty MR
// class generated here exists only so the iOS framework gets the
// `copyFrameworkResourcesToApp` task that copies the transitive
// :shared:resources bundle into the .app at Xcode build time. Distinct from
// the :shared:resources MR package (id.nearyou.resources) to avoid collision.
multiplatformResources {
    resourcesPackage.set("id.nearyou.app.frameworkresources")
}

android {
    namespace = "id.nearyou.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "id.nearyou.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}
