# funecomp

Android remote for [funeos](https://github.com/seilent/funeos) on the Hidizs AP80 and TempoTec V1, over Bluetooth.
Playback, volume, queue, library, settings and EQ from the phone. Artist photos are fetched on the phone and pushed to
the device as the Now Playing backdrop.

## Setup

1. On the device: Settings > System > PHONE APP on, then PAIR PHONE APP.
2. Pair the phone from its Bluetooth settings and confirm the code on both screens.
3. Open funecomp: it shows Connection until the device is linked. Pick the device under DEVICE.

Artist art sources (TheAudioDB, Discogs, fanart.tv, YouTube, Deezer) are off by default: Home > Artist art.

Android 12 or newer.

## Build

```
./gradlew assembleRelease
```

Release signing reads `app/keystores/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`);
without it the release APK is unsigned.

## License

GPL-3.0-or-later. `third_party/funeos/` (protocol spec and UI tokens) is also MIT. Selawik is under the SIL Open Font
License 1.1 (`app/src/main/assets/OFL-Selawik.txt`).
