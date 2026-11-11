import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Platform interfaces use real default methods; don't generate override stubs for them
        // (those stubs would show up as overrides of internal/experimental API).
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.6")
        // Optional at runtime (see plugin.xml); needed on the compile classpath for LocalTerminalCustomizer.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.ahin042.dinowalker"
        name = "Dino Walker"
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
        vendor {
            name = "ahin042"
            url = "https://github.com/ahin042"
        }
    }

    pluginVerification {
        // Fail on everything except plain deprecations: LocalTerminalCustomizer is deprecated since
        // 2026.1 and its replacement (ShellExecOptionsCustomizer) is still @ApiStatus.Experimental.
        // Scheduled-for-removal, internal and experimental usages still fail the build.
        failureLevel = FailureLevel.ALL.filterNot { it == FailureLevel.DEPRECATED_API_USAGES }
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.3")
            create(IntelliJPlatformType.WebStorm, "2026.3")
        }
    }
}
