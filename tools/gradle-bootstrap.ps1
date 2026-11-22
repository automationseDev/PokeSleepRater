param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
$homeDir = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
$root = Join-Path $homeDir 'pokesleep-bootstrap'
$gradleHome = Join-Path $root 'gradle-8.9'
$gradleExe = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    $archive = Join-Path $root 'gradle-8.9-bin.zip'
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile $archive
    Expand-Archive -Path $archive -DestinationPath $root -Force
    Remove-Item $archive
}
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & $gradleExe @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
