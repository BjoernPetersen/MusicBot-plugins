# MusicBot-gplaymusic

A Google Play Music provider for the [MusicBot](https://github.com/BjoernPetersen/MusicBot).

## Requirements

In order to be able to use this plugin, you will need an active AllAccess subscription to Google's
music streaming service [GooglePlayMusic](https://play.google.com/music/listen).

## Installation

1. Install a version of the MusicBot as well as an Mp3PlaybackFactory
(e.g. [MusicBot-mpv](../musicbot-mpv))
2. Copy the plugin jar (`musicbot-gplaymusic*.jar`) into the plugins folder.
3. Start the MusicBot and configure this plugin:
    1. **Username:** Your Google username or email.
    2. **Password:** Your Google password or, if you are using 2-factor-authentication,
    an App-Password created [here](https://support.google.com/accounts/answer/185833).
    3. **AndroidID:** The IMEI of an android phone, that recently had GooglePlayMusic installed.<br>
    _In Android 7 it can be found in Settings -> About Phone -> Status -> IMEI information. This should be similar for other versions_
    4. **Song Directory:** The Plugin will temporarily save downloaded songs in this directory.
    On closure this directory will be deleted.
    5. **Quality:** Sets the bitrate for the songs that will be streamed.
    6. **Cache Time:** Sets the time a song stays cached without access until it is deleted.
