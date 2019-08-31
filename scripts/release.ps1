Param (
    [parameter(Mandatory = $true)]
    [String]
    $Name
)

. scripts/common.ps1

$LowerName = $Name.ToLower()
$DirName = Get-DirName -Name $Name

# Find version
$Version = Find-Version -DirName $DirName
$TagName = "v$Version-$LowerName"

git add $DirName
git commit -m "Release $Name $Version"
git tag -s -m "Release $LowerName $Version" $TagName
git push
git push origin $TagName

gw ":${DirName}:shadowJar"
$FullDirPath = (Resolve-Path $DirName).Path
Invoke-Item $FullDirPath\build\libs
