val appProject = project(":app")
val javaHome = org.gradle.internal.jvm.Jvm.current().javaHome
val currentOs = org.gradle.internal.os.OperatingSystem.current()!!

plugins {
    `cpp-library`
}

library {
    linkage = listOf(Linkage.SHARED)

    targetMachines.addAll(
        machines.windows.x86_64,
        machines.linux.x86_64,
        machines.macOS.x86_64
    )

    source.from(file("src/main/cpp"))
    privateHeaders.from(file("src/main/include"))
    publicHeaders.from(file("src/main/public"))
}

tasks.withType<CppCompile>().configureEach {
    includes.from(
        javaHome.resolve("include"),
        when {
            currentOs.isWindows -> file("$javaHome/include/win32")
            currentOs.isLinux -> file("$javaHome/include/linux")
            currentOs.isMacOsX -> file("$javaHome/include/darwin")
            else -> throw GradleException("Unsupported platform: $currentOs")
        },
        file("src/main/include"),
        file("src/main/public")
    )

    compilerArgs.addAll(
        listOf(
            "-std=c++17",
            "-Wall",
            "-Wextra",
            "-O2",
            "-D_JNI_IMPLEMENTATION_"
        )
    )

    if (currentOs.isWindows) {
        compilerArgs.addAll(
            listOf(
                "-D_WIN32",
                "-D_WINDOWS",
                "-D_WIN32_WINNT=0x0601"
            )
        )
    } else if (currentOs.isLinux) {
        compilerArgs.addAll(
            listOf(
                "-D_LINUX",
                "-fPIC"
            )
        )
    } else if (currentOs.isMacOsX) {
        compilerArgs.addAll(
            listOf(
                "-D_DARWIN",
                "-fPIC"
            )
        )
    }
}

tasks.withType<LinkSharedLibrary>().configureEach {
    val jawtLib = when {
        currentOs.isWindows -> {
            file("$javaHome/lib/jawt.lib").takeIf { it.exists() }
                ?: file("$javaHome/lib/jawt.dll")
        }

        currentOs.isLinux -> {
            file("$javaHome/lib/libjawt.so")
        }

        currentOs.isMacOsX -> {
            file("$javaHome/lib/libjawt.dylib")
        }

        else -> throw GradleException("Unsupported platform: $currentOs")
    }

    if (jawtLib.exists()) {
        linkerArgs.add(jawtLib.absolutePath)
        logger.lifecycle("Linking with JAWT: ${jawtLib.absolutePath}")
    } else {
        logger.warn("JAWT library not found at: $jawtLib")

        val alternativeJawt = when {
            currentOs.isWindows ->
                file("$javaHome/lib/jawt.dll")

            currentOs.isLinux ->
                file("/usr/lib/jvm/default-java/lib/libjawt.so")

            currentOs.isMacOsX ->
                file("/Library/Java/JavaVirtualMachines/*/Contents/Home/lib/libjawt.dylib")

            else -> null
        }

        if (alternativeJawt?.exists() == true) {
            linkerArgs.add(alternativeJawt.absolutePath)
            logger.lifecycle("Using alternative JAWT: ${alternativeJawt.absolutePath}")
        }
    }

    linkerArgs.add("-L$javaHome/lib")

    if (currentOs.isWindows) {
        linkerArgs.addAll(
            listOf(
                "-lgdi32",
                "-luser32",
                "-lkernel32",
                "-lcomctl32",
                "-luuid",
                "-lole32",
                "-loleaut32",
                "-ldwmapi",
                "-static-libgcc",
                "-static-libstdc++"
            )
        )

        linkedFile.set(layout.buildDirectory.file("libs/native.dll"))
    } else if (currentOs.isLinux) {
        linkerArgs.addAll(
            listOf(
                "-ldl",
                "-lpthread"
            )
        )

        linkedFile.set(layout.buildDirectory.file("libs/libnative.so"))

    } else if (currentOs.isMacOsX) {
        linkerArgs.addAll(
            listOf(
                "-ldl",
                "-lpthread",
                "-framework", "JavaVM"
            )
        )

        linkedFile.set(layout.buildDirectory.file("libs/libnative.dylib"))
    }
}
