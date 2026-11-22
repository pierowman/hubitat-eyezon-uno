[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $TestArgs
)

# Runs the test suite. Finds Groovy on PATH, or in the default winget install location.
$groovy = Get-Command groovy -ErrorAction SilentlyContinue
if (-not $groovy) {
    $default = "C:\Program Files (x86)\Groovy\bin"
    if (Test-Path "$default\groovy.bat") { $env:Path += ";$default" }
}
if (-not (Get-Command groovy -ErrorAction SilentlyContinue)) {
    Write-Error "Groovy was not found. Install it with: winget install --id Apache.Groovy.4 -e"
    exit 2
}

# Groovy needs a JDK; pick up a winget-installed Temurin one if JAVA_HOME isn't set.
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

& groovy "$PSScriptRoot\run.groovy" @TestArgs
exit $LASTEXITCODE
