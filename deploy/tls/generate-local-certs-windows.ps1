[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$PublicIp,

    [string]$OutputDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.net-server\tls'),

    [string]$OpenSslPath,

    [switch]$SkipPersistEnvironment
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Resolve-OpenSslPath {
    param([string]$ConfiguredPath)

    if (-not [string]::IsNullOrWhiteSpace($ConfiguredPath)) {
        if (-not (Test-Path -LiteralPath $ConfiguredPath -PathType Leaf)) {
            throw "OpenSSL does not exist: $ConfiguredPath"
        }
        return (Resolve-Path -LiteralPath $ConfiguredPath).Path
    }

    $command = Get-Command openssl -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $candidates = @(
        (Join-Path $env:ProgramFiles 'Git\mingw64\bin\openssl.exe'),
        (Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    throw 'OpenSSL was not found. Install Git for Windows or pass -OpenSslPath.'
}

function Invoke-OpenSsl {
    param([string[]]$Arguments)

    & $script:ResolvedOpenSslPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed with exit code $LASTEXITCODE."
    }
}

function Join-PemFiles {
    param(
        [string[]]$InputFiles,
        [string]$OutputFile
    )

    $outputStream = [System.IO.File]::Open(
        $OutputFile,
        [System.IO.FileMode]::Create,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None)
    try {
        foreach ($inputFile in $InputFiles) {
            $bytes = [System.IO.File]::ReadAllBytes($inputFile)
            $outputStream.Write($bytes, 0, $bytes.Length)
            if ($bytes.Length -eq 0 -or $bytes[$bytes.Length - 1] -ne 10) {
                $outputStream.WriteByte(10)
            }
        }
    } finally {
        $outputStream.Dispose()
    }
}

if ($env:OS -ne 'Windows_NT') {
    throw 'This script supports Windows only. Use generate-local-certs.sh on macOS/Linux.'
}

$parsedIp = $null
if (-not [System.Net.IPAddress]::TryParse($PublicIp, [ref]$parsedIp) -or
    $parsedIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "PublicIp must be a valid IPv4 address: $PublicIp"
}

$localIpExists = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -eq $PublicIp }
if ($null -eq $localIpExists) {
    throw "PublicIp is not assigned to a local network adapter: $PublicIp"
}

$script:ResolvedOpenSslPath = Resolve-OpenSslPath -ConfiguredPath $OpenSslPath
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$opensslConfig = Join-Path $PSScriptRoot 'openssl-local.cnf'
$caKey = Join-Path $resolvedOutputDirectory 'chat-storage-local-ca.key'
$caCertificate = Join-Path $resolvedOutputDirectory 'chat-storage-local-ca.crt'
$serverKey = Join-Path $resolvedOutputDirectory 'net-server.key'
$serverRequest = Join-Path $resolvedOutputDirectory 'net-server.csr'
$serverCertificate = Join-Path $resolvedOutputDirectory 'net-server.crt'
$serverPem = Join-Path $resolvedOutputDirectory 'net-server.pem'
$keyStore = Join-Path $resolvedOutputDirectory 'net-server.p12'
$caDer = Join-Path $resolvedOutputDirectory 'chat-storage-local-ca.der'

$env:NET_SERVER_PUBLIC_IP = $PublicIp
$processPassword = [Environment]::GetEnvironmentVariable(
    'NET_SERVER_TLS_KEYSTORE_PASSWORD',
    'Process')

Invoke-OpenSsl -Arguments @(
    'req', '-new', '-x509', '-newkey', 'rsa:3072', '-nodes', '-sha256', '-days', '3650',
    '-config', $opensslConfig, '-extensions', 'ca_extensions',
    '-subj', '/CN=ChatStorage Local Test CA',
    '-keyout', $caKey, '-out', $caCertificate
)

Invoke-OpenSsl -Arguments @(
    'req', '-new', '-newkey', 'rsa:3072', '-nodes', '-sha256',
    '-config', $opensslConfig,
    '-keyout', $serverKey, '-out', $serverRequest
)

Invoke-OpenSsl -Arguments @(
    'x509', '-req', '-sha256', '-days', '397',
    '-in', $serverRequest,
    '-CA', $caCertificate, '-CAkey', $caKey, '-CAcreateserial',
    '-extfile', $opensslConfig, '-extensions', 'server_extensions',
    '-out', $serverCertificate
)

Join-PemFiles -InputFiles @($serverKey, $serverCertificate, $caCertificate) -OutputFile $serverPem

$opensslVersion = (& $script:ResolvedOpenSslPath version 2>&1 | Out-String).Trim()
$pkcs12Arguments = @('pkcs12', '-export')
if ($opensslVersion -match '^OpenSSL\s+3\.') {
    # OpenSSL 3 defaults are not readable by the JDK 8 PKCS12 implementation.
    $pkcs12Arguments += '-legacy'
}
$passwordArgument = 'pass:'
if (-not [string]::IsNullOrEmpty($processPassword)) {
    $passwordArgument = 'env:NET_SERVER_TLS_KEYSTORE_PASSWORD'
}
$pkcs12Arguments += @(
    '-passout', $passwordArgument,
    '-inkey', $serverKey,
    '-in', $serverCertificate,
    '-certfile', $caCertificate,
    '-out', $keyStore
)
Invoke-OpenSsl -Arguments $pkcs12Arguments

Invoke-OpenSsl -Arguments @(
    'x509', '-in', $caCertificate, '-outform', 'der', '-out', $caDer
)
Invoke-OpenSsl -Arguments @(
    'verify', '-x509_strict', '-CAfile', $caCertificate, $serverCertificate
)

if (-not $SkipPersistEnvironment) {
    [Environment]::SetEnvironmentVariable('NET_SERVER_PUBLIC_IP', $PublicIp, 'User')
    [Environment]::SetEnvironmentVariable('NET_SERVER_TLS_KEYSTORE', $keyStore, 'User')

    if ([string]::IsNullOrEmpty($processPassword)) {
        [Environment]::SetEnvironmentVariable('NET_SERVER_TLS_KEYSTORE_PASSWORD', $null, 'User')
    } else {
        Write-Warning 'The keystore password was not persisted. Set NET_SERVER_TLS_KEYSTORE_PASSWORD before startup.'
    }
}

Write-Output "Windows TLS keystore created: $keyStore"
Write-Output "Client CA certificate: $caCertificate"
Write-Output "Bind address: $PublicIp"
Write-Output "OpenSSL: $opensslVersion"
if (-not $SkipPersistEnvironment) {
    Write-Output 'User environment variables were updated. Restart IntelliJ before startup.'
}
