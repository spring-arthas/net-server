[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$JavaHome = $env:JAVA_HOME
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Get-EnvironmentValue {
    param([string]$Name)

    $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue
    }
    return [Environment]::GetEnvironmentVariable($Name, 'User')
}

if ($env:OS -ne 'Windows_NT') {
    throw 'This script supports Windows only. Use run-net-server-zulu8.sh on macOS/Linux.'
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $PSScriptRoot '..\target\net-server-1.0-SNAPSHOT.jar'
}

$publicIp = Get-EnvironmentValue -Name 'NET_SERVER_PUBLIC_IP'
$keyStore = Get-EnvironmentValue -Name 'NET_SERVER_TLS_KEYSTORE'

$resolvedJarPath = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJarPath -PathType Leaf)) {
    throw "Application JAR does not exist: $resolvedJarPath. Run mvn clean package -DskipTests first."
}

$javaExecutable = $null
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $candidate = Join-Path $JavaHome 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $javaExecutable = $candidate
    }
}
if ($null -eq $javaExecutable) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw 'java.exe was not found. Set JAVA_HOME or add the JDK 8 bin directory to PATH.'
    }
    $javaExecutable = $javaCommand.Source
}

# Windows locks an executable JAR while the JVM is running. Running the file
# directly from target prevents IDEA or Maven from cleaning and rebuilding it.
# Stage a private runtime copy outside the project so target remains writable.
$runtimeDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $env:USERPROFILE '.net-server\runtime'))
if (-not (Test-Path -LiteralPath $runtimeDirectory -PathType Container)) {
    [void](New-Item -ItemType Directory -Path $runtimeDirectory -Force)
}
$runtimeJarName = '{0}-{1}-{2}.jar' -f `
    [System.IO.Path]::GetFileNameWithoutExtension($resolvedJarPath), `
    $PID, `
    [Guid]::NewGuid().ToString('N')
$runtimeJarPath = [System.IO.Path]::GetFullPath(
    (Join-Path $runtimeDirectory $runtimeJarName))
$runtimePrefix = $runtimeDirectory.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if (-not $runtimeJarPath.StartsWith(
        $runtimePrefix,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Resolved runtime JAR path is outside the runtime directory.'
}
Copy-Item -LiteralPath $resolvedJarPath -Destination $runtimeJarPath

if (-not [string]::IsNullOrWhiteSpace($publicIp)) {
    $env:NET_SERVER_PUBLIC_IP = $publicIp
}
if (-not [string]::IsNullOrWhiteSpace($keyStore)) {
    $env:NET_SERVER_TLS_KEYSTORE = $keyStore
}
$userPassword = [Environment]::GetEnvironmentVariable('NET_SERVER_TLS_KEYSTORE_PASSWORD', 'User')
if ($null -eq [Environment]::GetEnvironmentVariable('NET_SERVER_TLS_KEYSTORE_PASSWORD', 'Process') -and
    $null -ne $userPassword) {
    $env:NET_SERVER_TLS_KEYSTORE_PASSWORD = $userPassword
}

Write-Output "Java: $javaExecutable"
Write-Output "Runtime JAR: $runtimeJarPath"
if ([string]::IsNullOrWhiteSpace($keyStore)) {
    Write-Output 'TLS keystore: auto (resolved by Java from user.home)'
} else {
    Write-Output "TLS keystore: $keyStore"
}
if ([string]::IsNullOrWhiteSpace($publicIp)) {
    Write-Output 'TLS bind address: auto (resolved by Java from active network interfaces)'
} else {
    Write-Output "TLS bind address: $publicIp"
}

$javaExitCode = 1
try {
    & $javaExecutable -jar $runtimeJarPath
    $javaExitCode = $LASTEXITCODE
} finally {
    if (Test-Path -LiteralPath $runtimeJarPath -PathType Leaf) {
        Remove-Item -LiteralPath $runtimeJarPath -Force
    }
}
exit $javaExitCode
