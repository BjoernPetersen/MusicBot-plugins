Param (
    [parameter(Mandatory = $true)]
    [String]
    $Name
)

. scripts/common.ps1

$DirName = Get-DirName $Name
$Version = Find-Version $DirName

git add $DirName
git commit -m "Update $Name to $Version"
