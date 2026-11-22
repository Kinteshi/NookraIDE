<#
.SYNOPSIS
    Hosts the Nookra IDE daemon on Windows through WSL2, reachable by the app over the LAN.

.DESCRIPTION
    Runs scripts/install-native-daemon.sh --remote inside a WSL2 distro (systemd enabled), then
    makes the daemon's port reachable from the network:
      * networkingMode=mirrored (.wslconfig): a Windows Firewall rule and a Hyper-V firewall rule;
      * NAT (the default): a port proxy from the Windows LAN address to the distro, refreshed at
        every logon because the distro's address changes after restarts.
    A logon task also keeps the distro running so the daemon stays up.
    Run from an elevated PowerShell. See docs/HOSTING-THE-DAEMON.md.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install-host-daemon-wsl.ps1 -Distro Ubuntu -Workspace ~/Projects
#>
param(
    [string]$Distro,
    [int]$Port = 19870,
    [string]$Workspace = "~/Projects",
    # Path of an x86_64 Linux avf-daemon inside the distro; built with cargo when omitted.
    [string]$DaemonPath
)
$ErrorActionPreference = "Stop"
$TaskName = "AVF Studio daemon (WSL)"
$RuleName = "AVF Studio daemon"
$WslVmCreatorId = "{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}"

function Invoke-Wsl([string]$Command) {
    $output = & wsl.exe -d $Distro -- bash -lc $Command
    if ($LASTEXITCODE -ne 0) { throw "WSL command failed ($LASTEXITCODE): $Command" }
    return $output
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script from an elevated PowerShell (it adds firewall rules, a port proxy and a logon task)."
}
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "WSL isn't installed. Install it with: wsl --install"
}
if (-not $Distro) {
    # `wsl -l -q` prints UTF-16; strip the NULs to get the default (first) distro name.
    $Distro = ((& wsl.exe -l -q) -replace "`0", "" | Where-Object { $_.Trim() } | Select-Object -First 1).Trim()
}
Write-Host "Using WSL distro '$Distro'."

if ((Invoke-Wsl "ps -p 1 -o comm=").Trim() -ne "systemd") {
    throw "systemd isn't enabled in '$Distro'. Add '[boot]' and 'systemd=true' to /etc/wsl.conf, run 'wsl --shutdown', then rerun this script."
}

# The repository as the distro sees it (works whether it lives in Windows or in the distro).
$repo = (Invoke-Wsl "wslpath -a '$((Split-Path -Parent $PSScriptRoot) -replace "'", "'\''")'").Trim()
if (-not $DaemonPath) {
    $DaemonPath = "$repo/target/release/nookra-daemon"
    if ((Invoke-Wsl "test -x '$DaemonPath' && echo yes || echo no").Trim() -ne "yes") {
        Write-Host "Building the daemon in WSL (needs Rust: https://rustup.rs)…"
        Invoke-Wsl "cd '$repo' && cargo build --release -p avf-daemon" | Out-Host
    }
}

# The address the tablet uses: Windows' own LAN address, not the distro's.
$lan = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } | Select-Object -First 1
if (-not $lan) { throw "Couldn't find a network adapter with a default gateway." }
$address = $lan.IPv4Address.IPAddress
Write-Host "This computer's LAN address: $address"

$wslConfig = Join-Path $env:USERPROFILE ".wslconfig"
$mirrored = (Test-Path $wslConfig) -and ((Get-Content $wslConfig -Raw) -match "(?im)^\s*networkingMode\s*=\s*mirrored")

# Firewall: let the LAN reach the port (Windows Firewall; Hyper-V firewall for mirrored mode).
Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $RuleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private, Domain | Out-Null
if ($mirrored -and (Get-Command New-NetFirewallHyperVRule -ErrorAction SilentlyContinue)) {
    Get-NetFirewallHyperVRule -Name "AVFStudioDaemon" -ErrorAction SilentlyContinue | Remove-NetFirewallHyperVRule
    New-NetFirewallHyperVRule -Name "AVFStudioDaemon" -DisplayName $RuleName -Direction Inbound -VMCreatorId $WslVmCreatorId -Protocol TCP -LocalPorts $Port | Out-Null
}

# A logon task keeps the distro alive and (NAT mode) points the port proxy at its current address.
$startup = Join-Path $env:LOCALAPPDATA "AVFStudio\avf-wsl-start.ps1"
New-Item -ItemType Directory -Force -Path (Split-Path $startup) | Out-Null
$proxyRefresh = if ($mirrored) { "" } else {
@"
`$wslIp = ((& wsl.exe -d '$Distro' -- hostname -I) -split ' ')[0].Trim()
netsh interface portproxy delete v4tov4 listenport=$Port listenaddress=0.0.0.0 | Out-Null
netsh interface portproxy add v4tov4 listenport=$Port listenaddress=0.0.0.0 connectport=$Port connectaddress=`$wslIp | Out-Null
"@
}
Set-Content -Path $startup -Encoding UTF8 -Value @"
# Written by install-host-daemon-wsl.ps1: keeps '$Distro' running for the Nookra IDE daemon.
Start-Process -WindowStyle Hidden -FilePath wsl.exe -ArgumentList '-d', '$Distro', '--exec', 'sleep', 'infinity'
Start-Sleep -Seconds 5
$proxyRefresh
"@
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$startup`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -RunLevel Highest -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

# Install the daemon in the distro; it prints the connection string for the app.
Invoke-Wsl "bash '$repo/scripts/install-native-daemon.sh' --remote --port $Port --address $address '$DaemonPath' $Workspace" | Out-Host
Write-Host ""
Write-Host "Paste the avf:// connection string above into Nookra IDE: Connect > Set up or pair > Pair with computer."
Write-Host "New code later: wsl -d $Distro -- ~/.local/bin/nookra-daemon pair --remote ${address}:$Port"
