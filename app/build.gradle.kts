import cn.hutool.json.JSONArray
import cn.hutool.json.JSONObject

buildscript {
    repositories {
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath("cn.hutool:hutool-all:5.8.38")
    }
}

val nativeProject = project(":native")

plugins {
    java
    application
    `maven-publish`
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("io.franzbecker.gradle-lombok") version "3.0.0"
    id("com.github.jk1.dependency-license-report") version "2.5"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }

    repositories {
        mavenLocal()
    }
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web", "javafx.swing")
}

group = "org.a8043.simpleIDE"
version = "2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("org.a8043.simpleIDE.Main")
}

configurations.all {
    resolutionStrategy {
        force("org.slf4j:slf4j-api:1.7.30")
    }
}

val javafxVersion = javafx.version
val osName = System.getProperty("os.name").lowercase()
val javafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> "mac"
    osName.contains("linux") -> "linux"
    else -> throw GradleException("Unsupported OS for JavaFX: $osName")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.27.1")
    implementation("org.benf:cfr:0.152")
    implementation("org.apache.maven.shared:maven-invoker:3.3.0")
    implementation("org.apache.maven:maven-model:3.9.11")
    implementation("org.gradle:gradle-tooling-api:9.0.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")

    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-fxml:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-web:$javafxVersion:$javafxPlatform")
    implementation("org.fxmisc.richtext:richtextfx:0.11.7")
    implementation("io.github.typhon0:AnimateFX:1.3.0")

    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.20.0")

    implementation("cn.hutool:hutool-all:5.8.38")
    implementation("com.pivovarit:parallel-collectors:4.0.0")
    implementation("org.commonjava.googlecode.markdown4j:markdown4j:2.2-cj-1.1")

    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    testCompileOnly("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")
    testImplementation("junit:junit:4.13.1")
}

tasks.withType(JavaCompile::class.java).configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<JavaCompile> {
    doFirst {
        options.compilerArgs.addAll(
            listOf(
                "--module-path", classpath.asPath,
                "--add-modules", "maven.model",
                "--add-modules", "lombok",
                "--add-reads", "SimpleIDE.main=maven.model"
            )
        )
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"

    manifest {
        attributes["Main-Class"] = "org.a8043.simpleIDE.Main"
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = project.version
    }

    archiveClassifier.set("all")

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir.resolve("test")
    jvmArgs = listOf(
        "-Djava.library.path=${nativeProject.layout.buildDirectory.file("lib/main/debug/windows").get().asFile}",
        "--add-opens", "java.base/java.net=ALL-UNNAMED",
        "--add-opens", "javafx.graphics/javafx.scene=ALL-UNNAMED"
    )
}

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/main").get()

tasks.register("generateFiles") {
    val versionFile = generatedResourcesDir.file("version.json").asFile
    val imagesFile = generatedResourcesDir.file("images.json").asFile
    val imagesDir = projectDir.resolve("src/main/resources/images")

    doFirst {
        versionFile.parentFile.mkdirs()
    }

    doLast {
        val versionJson = JSONObject().set("ide", version).set("javafx", javafxVersion)
        versionFile.writeText(versionJson.toString())

        val imagesJson = JSONArray()
        imagesDir.listFiles().forEach { imagesJson.put(it.name) }
        imagesFile.writeText(imagesJson.toString())
    }
}

tasks.named<ProcessResources>("processResources") {
    sourceSets.main.get().resources.srcDir(generatedResourcesDir)
    dependsOn("generateFiles")
}

tasks.jar {
    from("build/reports/dependency-license") {
        into("META-INF/dependency-license")
    }
}

tasks.test {
    workingDir = File(rootDir, "test")
}
