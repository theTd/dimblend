# Runs the project's own suite for the two modules this job touched (Windows).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

# This machine's system JAVA_HOME points at JDK 11; Gradle needs 21 (see AGENTS.md).
$jdk21 = 'C:\Program Files\Java\jdk-21'
if (Test-Path (Join-Path $jdk21 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk21
}

& .\gradlew.bat :dimblend-spyglass:test :dimblend-experience:test @args
exit $LASTEXITCODE
