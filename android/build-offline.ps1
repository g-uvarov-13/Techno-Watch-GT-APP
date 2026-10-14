# Place this file in the public package's android directory.
# Uses the default WSL distribution; dependencies may download on first build.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments = @()
)

$ErrorActionPreference = "Stop"
if ($GradleArguments.Count -eq 0) {
    throw "Explicit Gradle tasks are required, for example: clean assembleDebug lintDebug"
}
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "WSL is required. Configure a Linux JDK 17 and Android SDK in its default distribution."
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "gradlew") -PathType Leaf)) {
    throw "Place build-offline.ps1 beside the public Android gradlew file."
}

function Quote-Bash([string] $Value) {
    if ($Value.IndexOf([char]0) -ge 0 -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "Multiline or NUL values are not supported."
    }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Convert-ToWslPath([string] $WindowsPath) {
    $converted = & wsl.exe -e wslpath -a -- $WindowsPath
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($converted -join ""))) {
        throw "Unable to convert a required path to the default WSL distribution."
    }
    return ($converted -join "`n").Trim()
}

$androidDirectory = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$linuxAndroidDirectory = Convert-ToWslPath $androidDirectory
$commands = @("set -e", "cd -- $(Quote-Bash $linuxAndroidDirectory)")

# These optional overrides must already be Linux-native paths, not Windows JDK/SDK binaries.
$linuxJavaHome = [Environment]::GetEnvironmentVariable("CHASOVOY_JAVA_HOME")
if (-not [string]::IsNullOrWhiteSpace($linuxJavaHome)) {
    if (-not $linuxJavaHome.StartsWith("/")) { throw "CHASOVOY_JAVA_HOME must be a Linux absolute path." }
    $commands += "export JAVA_HOME=$(Quote-Bash $linuxJavaHome)"
    $commands += 'export PATH="$JAVA_HOME/bin:$PATH"'
}
$linuxSdkRoot = [Environment]::GetEnvironmentVariable("CHASOVOY_ANDROID_SDK_ROOT")
if (-not [string]::IsNullOrWhiteSpace($linuxSdkRoot)) {
    if (-not $linuxSdkRoot.StartsWith("/")) { throw "CHASOVOY_ANDROID_SDK_ROOT must be a Linux absolute path." }
    $commands += "export ANDROID_SDK_ROOT=$(Quote-Bash $linuxSdkRoot)"
    $commands += "export ANDROID_HOME=$(Quote-Bash $linuxSdkRoot)"
}

$signingPath = [Environment]::GetEnvironmentVariable("CHASOVOY_SIGNING_PROPERTIES")
if (-not [string]::IsNullOrWhiteSpace($signingPath)) {
    if ($signingPath.StartsWith("/")) {
        $linuxSigningPath = $signingPath
    } else {
        $resolvedSigningPath = (Resolve-Path -LiteralPath $signingPath).Path
        $linuxSigningPath = Convert-ToWslPath $resolvedSigningPath
    }
    # storeFile inside properties must ALSO be a Linux-native path. Never read/log passwords here.
    $commands += "export CHASOVOY_SIGNING_PROPERTIES=$(Quote-Bash $linuxSigningPath)"
}
$quotedArguments = ($GradleArguments | ForEach-Object { Quote-Bash $_ }) -join " "
# bash invokes a wrapper whose executable bit may have been lost in a ZIP archive.
$commands += "exec bash ./gradlew --no-daemon $quotedArguments"
$scriptText = ($commands -join "`n") + "`n"

# stdin avoids Windows native command-line quoting of paths, apostrophes and task arguments.
$previousOutputEncoding = $OutputEncoding
try {
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $scriptText | & wsl.exe -e bash -l -s
    $buildExitCode = $LASTEXITCODE
} finally {
    $OutputEncoding = $previousOutputEncoding
}
exit $buildExitCode
