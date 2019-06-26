# MusicBot-sysvolume

Provides system master volume control by calling CLI commands.

## Config

The CLI commands to access and change the master volume are highly system dependent.
You need to provide the commands and an extraction regex pattern in order for this plugin
to work.

### Windows example

Windows doesn't have a reasonable built-in CLI interface to control the master volume.
It is recommended to install the [`setvol`](https://www.rlatour.com/setvol/) tool by 
Rob Latour and add it to your `PATH`.

The config should then look like this:

| key        | value
| ---------- | -----
| valueMode  | `Percent`
| getCommand | `setvol report`
| getPattern | `Master volume level = (\d+)`
| setCommand | `setvol <volume>`

### Linux/ALSA

| key        | value
| ---------- | -----
| valueMode  | `Percent`
| getCommand | `amixer sget 'Master'`
| getPattern | `TODO`
| setCommand | `amixer sset 'Master' <volume>%`

### Linux/PulseAudio

| key        | value
| ---------- | -----
| valueMode  | `Percent`
| getCommand | `TODO`
| getPattern | `TODO`
| setCommand | `pactl set-sink-volume 0 <volume>%`
