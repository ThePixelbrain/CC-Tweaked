// SPDX-FileCopyrightText: 2023 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

import com.diffplug.gradle.spotless.FormatExtension
import com.diffplug.spotless.LineEnding
import net.fabricmc.loom.LoomGradleExtension
import java.nio.charset.StandardCharsets

plugins {
    alias(libs.plugins.voldeloom)
    alias(libs.plugins.spotless)
    id("com.gradleup.shadow") version "8.3.5"
}

val mcVersion: String by extra

fun git(vararg args: String): String? = try {
    val output = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue == 0) output.standardOutput.asText.get().trim() else null
} catch (e: Exception) {
    null
}

/**
 * Derive a version from git, following GTNH's scheme. A clean checkout of a release tag (e.g. 1.115.2) uses the tag
 * as-is. Otherwise, the branch, commits since the tag and commit hash are appended, e.g.
 * 1.115.2-mc-1-4-7.3+06f06abd8-dirty. Only bare x.y.z tags count, so upstream's v1.4.7-x.y.z tags are ignored.
 */
fun gitVersion(): String {
    val hash = git("rev-parse", "--short", "HEAD") ?: return "0.0.0-unknown"
    val dirty = !git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()

    val describe = git("describe", "--tags", "--long", "--match", "[0-9]*.[0-9]*.[0-9]*")
        ?.let { Regex("""^(.+)-(\d+)-g[0-9a-f]+$""").find(it) }
    val tag = describe?.groupValues?.get(1) ?: "0.0.0"
    val distance = describe?.groupValues?.get(2)?.toInt() ?: git("rev-list", "--count", "HEAD")?.toInt() ?: 0

    val branch = git("rev-parse", "--abbrev-ref", "HEAD").takeUnless { it == null || it == "HEAD" } ?: "detached"
    val branchName = branch.replace(Regex("[^a-zA-Z0-9-]+"), "-")
    val dirtySuffix = if (dirty) "-dirty" else ""

    return when {
        distance > 0 -> "$tag-$branchName.$distance+$hash$dirtySuffix"
        dirty -> "$tag-$branchName+$hash-dirty"
        else -> tag
    }
}

// CI passes the release tag as -PmodVersion (or ORG_GRADLE_PROJECT_modVersion); otherwise the version comes from git.
val modVersion = providers.gradleProperty("modVersion").getOrElse(gitVersion())

group = "cc.tweaked"
version = modVersion

base.archivesName.convention("cc-tweaked-$mcVersion")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

    withSourcesJar()
}

val runtimeToolchain = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(8))
}

repositories {
    mavenCentral()

    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

volde {
    runs {
        named("client") {
            programArg("SquidDev")
            property("fml.coreMods.load", "cc.tweaked.patch.CorePlugin")
        }
    }
}

tasks.withType(net.fabricmc.loom.task.RunTask::class.java).configureEach {
    javaLauncher.set(runtimeToolchain)
}

configurations {
    val shade by registering
    compileOnly { extendsFrom(shade.get()) }
}

val buildTools by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    forge("net.minecraftforge:forge:${libs.versions.forge.get()}:universal@zip")

    mappings(
        volde.layered {
            importBaseZip("net.minecraftforge:forge:${libs.versions.forge.get()}:src@zip")
            removeClasses(listOf("bar", "bas"))
        },
    )

    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("org.jetbrains:annotations:24.0.1")
    compileOnly(libs.jspecify)
    modImplementation("maven.modrinth:computercraft:1.50@zip")
    "shade"("cc.tweaked:cobalt")
    "shade"(libs.bundles.netty)

    "buildTools"("cc.tweaked.cobalt:build-tools")
}

// Point compileJava to emit to classes/uninstrumentedJava/main, and then add a task to instrument these classes,
// saving them back to the original class directory. This is held together with so much string :(.
val mainSource = sourceSets.main.get()
val javaClassesDir = mainSource.java.classesDirectory.get()
val untransformedClasses = project.layout.buildDirectory.dir("classes/uninstrumentedJava/main")

val instrumentJava = tasks.register(mainSource.getTaskName("Instrument", "Java"), JavaExec::class) {
    dependsOn(tasks.compileJava)
    inputs.dir(untransformedClasses).withPropertyName("inputDir")
    outputs.dir(javaClassesDir).withPropertyName("outputDir")

    // Run under Java 8, so we can check compatibility of methods.
    javaLauncher.set(runtimeToolchain)
    mainClass.set("cc.tweaked.cobalt.build.MainKt")
    classpath = buildTools

    args = listOf(
        untransformedClasses.get().asFile.absolutePath,
        javaClassesDir.asFile.absolutePath,
    )

    doFirst { project.delete(javaClassesDir) }
}

mainSource.compiledBy(instrumentJava)
tasks.compileJava {
    destinationDirectory.set(untransformedClasses)
    finalizedBy(instrumentJava)
}

tasks.withType(AbstractArchiveTask::class.java).configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirMode = Integer.valueOf("755", 8)
    fileMode = Integer.valueOf("664", 8)
}

// Override remapJarForRelease, and then manually configure all tasks to have the right classifiers.
tasks.remapJarForRelease {
    archiveClassifier = ""
    input = tasks.shadowJar.flatMap { it.archiveFile }
}

tasks.jar { archiveClassifier = "dev-slim" }

tasks.shadowJar {
    archiveClassifier = "dev"
    manifest {
        attributes(
            "FMLCorePlugin" to "cc.tweaked.patch.CorePlugin",
            "FMLCorePluginContainsFMLMod" to "true",
        )
    }

    configurations = listOf(project.configurations["shade"])
    relocate("io.netty", "cc.tweaked.vendor.netty")
    minimize()
}

project.afterEvaluate {
    // Remove tasks.jar from the runtime classpath and add shadowJar instead.
    val field = LoomGradleExtension::class.java.getDeclaredField("unmappedModsBuilt")
    field.isAccessible = true
    (field.get(volde) as MutableList<*>).clear()
    volde.addUnmappedMod(tasks.shadowJar.get().archiveFile.get().asFile.toPath())
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcVersion", mcVersion)
    filesMatching("mcmod.info") {
        expand("version" to project.version, "mcVersion" to mcVersion)
    }
}

spotless {
    encoding = StandardCharsets.UTF_8
    lineEndings = LineEnding.UNIX

    fun FormatExtension.defaults() {
        endWithNewline()
        trimTrailingWhitespace()
        indentWithSpaces(4)
    }

    java {
        defaults()
        removeUnusedImports()
    }

    val ktlintConfig = mapOf(
        "ktlint_standard_no-wildcard-imports" to "disabled",
        "ij_kotlin_allow_trailing_comma" to "true",
        "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
    )

    kotlinGradle {
        defaults()
        ktlint().editorConfigOverride(ktlintConfig)
    }

    kotlin {
        defaults()
        ktlint().editorConfigOverride(ktlintConfig)
    }
}
