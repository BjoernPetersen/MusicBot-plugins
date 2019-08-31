Param (
    [parameter(Mandatory = $true)]
    [String]
    $Name
)

function gw([String] $gradleArgs) {
    if ($isWindows) {
        Return ./gradlew.bat $gradleArgs
    }
    else {
        Return ./gradle $gradleArgs
    }
}

$LowerName = $Name.ToLower()
$DirName = "musicbot-$LowerName"

$Dir = Get-Item $DirName
if (!$Dir.Exists) {
    Exit 1
}

# Find version
$Version = (gw(":${DirName}:properties") | rg "version:").toString().Substring("version: ".Length)
$TagName = "v$Version-$LowerName"

git add $DirName
git commit -m "Release $Name $Version"
git tag -s -m "Release $LowerName $Version" $TagName
git push
git push origin $TagName

gw(":${DirName}:shadowJar")
$FullDirPath = (Resolve-Path $DirName).Path
Invoke-Item $FullDirPath\build\libs
