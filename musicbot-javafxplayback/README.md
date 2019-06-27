# MusicBot-javafxPlayback

JavaFX-based plugin to provide MusicBot playback for various audio file formats.

## Note for Linux-Users:
You must install <code>GLIB 2.28</code> in order to run JavaFX Media.

You must install the following in order to support AAC audio, MP3 audio, H.264 video, and HTTP Live Streaming:
<code>libavcodec53</code> and <code>libavformat53</code> on Ubuntu Linux 12.04 or equivalent.

### On Ubuntu:
```bash
echo 'deb http://ftp.de.debian.org/debian/ wheezy main' >> /etc/apt/sources.list
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 8B48AD6246925553  7638D0442B90D010  6FB2A1C265FFB764
sudo apt-get install libavcodec53
```
