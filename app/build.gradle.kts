plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pisandbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pisandbox"
        minSdk = 26
        // targetSdk 28 on purpose (same trick as Termux): apps targeting API 29+ get the
        // "W^X" SELinux policy that forbids exec/mmap(PROT_EXEC) of files in the app's data dir.
        // proot's loader has to mmap the *guest* ELF binaries (which live in the extracted rootfs
        // under filesDir) as executable, so targeting 28 is what makes PRoot work. Sideloading a
        // targetSdk-28 app is still allowed on Android 15/16 (the floor is 24). See README.
        targetSdk = 28
        versionCode = 7
        versionName = "0.5.2"
        ndk { abiFilters += listOf("arm64-v8a") }
        // Where the source code lives (About screen, written source offer). Set in gradle.properties
        // (pisandbox.projectUrl) - change it with scripts/set-repo-owner.sh <owner>; -P overrides it.
        val projectUrl = (project.findProperty("pisandbox.projectUrl") as String?)
            ?: error("pisandbox.projectUrl missing from gradle.properties")
        if ("/OWNER/" in projectUrl && gradle.startParameter.taskNames.any { "Release" in it })
            logger.warn("w: PiSandbox: project URL is still the placeholder $projectUrl - run scripts/set-repo-owner.sh <owner> before publishing")
        buildConfigField("String", "PROJECT_URL", "\"$projectUrl\"")
    }

    // Release signing: a local keystore that never enters git (~/keys/pisandbox-release.jks, password in
    // ~/keys/pisandbox-release.pass, both chmod 600). Override with -Ppisandbox.keystore=/path. Without the
    // keystore, assembleRelease still builds but the APK is unsigned.
    val keyDir = File(System.getProperty("user.home"), "keys")
    val ks = file((project.findProperty("pisandbox.keystore") as String?) ?: File(keyDir, "pisandbox-release.jks").path)
    val ksPass = File(ks.parentFile, ks.nameWithoutExtension + ".pass")
    signingConfigs {
        if (ks.isFile && ksPass.isFile) create("release") {
            storeFile = ks
            storePassword = ksPass.readText().trim()
            keyAlias = "pisandbox"
            keyPassword = ksPass.readText().trim()
            enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }   // signed automatically with the SDK debug key
        release {
            // R8: shrink + optimise our code and the Java libraries; the terminal (JNI) classes are kept (proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // No git commit id in META-INF: the APK depends only on the sources, so the dist/ build and the
            // release-kit build of the same tree are byte-identical (and nothing local leaks into the APK).
            vcsInfo.include = false
        }
    }

    // Licence texts + third-party notices (repo-root legal/) are shipped in the APK for the About screen.
    sourceSets["main"].assets.srcDir(rootProject.file("legal"))

    // proot + its libs are shipped as lib*.so so the installer extracts them to
    // nativeLibraryDir (an exec-allowed location) — the standard Android 10+ way to ship helper executables.
    packaging { jniLibs { useLegacyPackaging = true } }

    // The rootfs .tar.xz is already compressed: store it as-is (also lets us openFd() it for progress).
    androidResources { noCompress += listOf("xz", "tar") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("pisandbox.screensDir", rootProject.file("dist/screens").path)
            it.systemProperty("pisandbox.framesDir", rootProject.file("build/splash-frames").path)
            it.systemProperty("pisandbox.examplesDir", rootProject.file("rootfs/runtime/usr/share/pisandbox/examples").path)
            // host build of termux's pty JNI (scripts/render-screens.sh) so screenshots show real terminal output
            it.systemProperty("java.library.path", rootProject.file("tools/hostjni").path)
            it.maxHeapSize = "2g"
        }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = true            // zero lint errors allowed
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Rebuild the small runtime overlay (GPIO shims, profile.d, examples + .shipped-hashes) into assets/runtime on
// every build, so a code change in rootfs/runtime can't ship stale. (The big Debian rootfs comes from fetch-assets.)
val buildRuntime by tasks.registering(Exec::class) {
    val root = rootProject.projectDir
    inputs.dir(File(root, "rootfs/runtime")); inputs.file(File(root, "scripts/build-runtime.sh"))
    outputs.file(File(projectDir, "src/main/assets/runtime/pisandbox-runtime.tar"))
    commandLine("bash", "-c", "\"$root/scripts/build-runtime.sh\" >/dev/null && mkdir -p src/main/assets/runtime && cp \"$root/build/runtime/pisandbox-runtime.tar\" src/main/assets/runtime/")
    workingDir = projectDir
}
tasks.named("preBuild") { dependsOn(buildRuntime) }
