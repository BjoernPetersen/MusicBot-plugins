function gw([String] $GradleArgs) {
    if ($isWindows) {
        Return ./gradlew.bat $GradleArgs
    }
    else {
        Return ./gradle $GradleArgs
    }
}

function Get-DirName([String] $Name) {
    $LowerName = $Name.ToLower()
    Return "musicbot-${LowerName}"
}

function Find-Version([parameter(Mandatory = $true)] [String] $DirName) {
    Try {
        Get-Item $DirName > $null
    }
    Catch {
        throw "Directory does not exist: $DirName"
    }
    Return (gw(":${DirName}:properties") | rg "version:").toString().Substring("version: ".Length)
}
