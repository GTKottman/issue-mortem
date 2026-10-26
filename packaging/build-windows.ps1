# Builds dist\issue-mortem-helper-<version>-windows-x64.msi on Windows.
# Needs Go and WiX 5: dotnet tool install --global wix --version 5.0.2
#   wix extension add -g WixToolset.Firewall.wixext/5.0.2 WixToolset.Util.wixext/5.0.2
param([string]$Version = "0.0.3")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
New-Item -ItemType Directory -Force dist | Out-Null
Push-Location helper
$env:CGO_ENABLED = "0"; $env:GOOS = "windows"; $env:GOARCH = "amd64"
go build -trimpath -ldflags "-s -w -H windowsgui" -o ..\packaging\windows\issue-mortem-helper.exe .
Pop-Location
Push-Location packaging\windows
wix build helper.wxs -arch x64 -d Version=$Version -ext WixToolset.Firewall.wixext -ext WixToolset.Util.wixext `
  -o ..\..\dist\issue-mortem-helper-$Version-windows-x64.msi
Pop-Location
Remove-Item packaging\windows\issue-mortem-helper.exe
Get-Item dist\*.msi
