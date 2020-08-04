Param (
    [parameter(Mandatory = $true)]
    [String[]]
    $Names,
    [switch]
    $Snapshot
)

. scripts/common.ps1

foreach ($Name in $Names) {
    $DirName = Get-DirName $Name
    [string]$Version = Find-Version $DirName
    if ($Version.EndsWith("-SNAPSHOT")) {
        $Version = $Version.Substring(0, $Version.Length - "-SNAPSHOT".Length)
    }

    [version]$V = $Version
    $NewVersion = "{0}.{1}.0" -f $V.Major, ($V.Minor + 1)
    if ($Snapshot) {
        $NewVersion = $NewVersion + "-SNAPSHOT"
    }

    $GradleLine = "`nversion = ""$NewVersion"""
    Set-Clipboard -Value $GradleLine
    Write-Host $GradleLine
    code "$DirName/build.gradle.kts" --wait

    git add $DirName
    git commit -m "Update $Name to $NewVersion"
}
