param([string]$Test = '*', [switch]$BuildApk)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$repoRoot = Split-Path $projectRoot
$gradle = Join-Path $repoRoot 'gradle-8.7\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw 'Gradle 8.7 is required beside android/.' }
$jdk = Join-Path $repoRoot 'tools\jdk-17.0.12+7'
if (-not (Test-Path $jdk)) { $jdk = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot' }
$oldJava = $env:JAVA_HOME
$oldOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $jdk
    # A short existing path avoids Windows AF_UNIX loopback failures in Java NIO.
    $env:JAVA_TOOL_OPTIONS = "$oldOptions -Djdk.net.unixdomain.tmpdir=C:\Windows\Temp -Djava.io.tmpdir=C:\Windows\Temp"
    $tasks = @('-p', $projectRoot, ':app:testDebugUnitTest', '--tests', $Test, '--console=plain', '--no-daemon')
    if ($BuildApk) { $tasks += ':app:assembleDebug' }
    & $gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $oldJava
    $env:JAVA_TOOL_OPTIONS = $oldOptions
}
