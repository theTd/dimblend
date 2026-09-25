# DimBlend Experience

A Minecraft mod for **Minecraft 1.21.1** powered by **NeoForge**.

## Toolchain

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.249（单仓统一，见根 `gradle.properties`） |
| ModDevGradle | 2.0.147 |
| Gradle (wrapper) | 9.2.1 |
| Java | 21 |
| Parchment mappings | 1.21.1:2024.11.17 |

Shared pins (MC/Neo/Parchment) live in the monorepo-root [`gradle.properties`](../gradle.properties);
only `mod_*` coordinates stay in this module's [`gradle.properties`](gradle.properties).

## Getting started

Requirements: JDK 21 (the Gradle wrapper at the repo root bootstraps everything else).
All commands run from the **repo root**:

```bat
:: Build this mod's jar (outputs to dimblend-experience/build/libs/)
gradlew.bat :dimblend-experience:build

:: Launch a dev client with the mod loaded
gradlew.bat :dimblend-experience:runClient

:: Launch a dev server
gradlew.bat :dimblend-experience:runServer

:: Run data generation (outputs to dimblend-experience/src/generated/resources)
gradlew.bat :dimblend-experience:runData
```

## Troubleshooting

- **"Gradle requires JVM 17 or later ... configured to use JVM 11"** — the wrapper prefers
  `JAVA_HOME` over the `java` on `PATH`. Point `JAVA_HOME` at a JDK 21 install for your shell:
  `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'` (PowerShell) or the equivalent for your OS.


## Project layout

```
src/main/java/dimblend/experience/  Mod sources (DimBlend.java is the @Mod entrypoint)
src/main/resources/                Static resources (assets/, data/)
src/main/resources/assets/dimblend_experience/lang/  Localization files
src/main/templates/META-INF/       neoforge.mods.toml template (properties expanded at build time)
src/generated/resources/           Data-generator output (added to the jar automatically)
```

The mod metadata in `src/main/templates/META-INF/neoforge.mods.toml` is generated from the
`mod_*` properties in `gradle.properties` by the `generateModMetadata` task — edit the
properties, not the generated file.

## Initial scaffold

Scaffolded from the official NeoForge MDK (NeoForgeMDKs `MDK-1.21-ModDevGradle` @ main, which
matches the `MDK-1.21.1` template's toolchain pins: ModDevGradle 2.0.147 / Gradle 9.2.1), with
1.21.1 version values from the MDK-1.21.1 template's `gradle.properties`.

The repo starts with the standard MDK example content (example block/item/creative tab and a
config spec) as a working reference; replace it with real DimBlend Experience features.
