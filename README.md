# Compy

An unofficial, native Android client for Rex terminal servers. Kotlin and Jetpack Compose on Android 14 and up, for phones, foldables and tablets. Not affiliated with the makers of Rex.

## Connect to a server

Compy reaches servers over Tailscale, so install the Tailscale app on your phone and join the same tailnet. Then, on the computer running Rex:

```sh
rex server tailscale on
rex server tailscale pair
```

In Compy, tap **Scan pairing code** and scan what it shows, or type the `ts.net` address. Release builds only use HTTPS.

## Features

- **Terminals:** live panes with scrollback, drawn by libghostty-vt.
- **Layout:** Rex's real split layout, with draggable dividers and an arrange mode. These edits change the layout for every connected client.
- **Sizes:** each session either fits terminals to this screen while Compy is open, or keeps another device's size with pan and zoom.
- **Servers:** all your servers in one list. Activity view groups panes by what needs you.
- **Commands:** tap `⌘` for a searchable palette of Compy's commands and the host's Rex actions; hold it to latch the modifier.
- **Terminal tools:** tap links, find in scrollback, and jump between prompts or copy a command's output when the shell marks them with OSC 133.
- **Notifications:** Compy can stay connected in the background and alert you when an agent needs you, finishes or fails. Open the pane from the notification, or reply to it directly.
- **Status:** progress rings, the current folder under each window, and a haptic on the terminal bell.
- **Input:** direct typing, a customizable key bar, a composer with voice typing, and hardware keyboard and mouse support.
- **Look:** the whole app takes its colors from the Rex host's terminal theme, or from your wallpaper if you prefer.
- **Android:** predictive back, a share target for text, app shortcuts, a home screen widget and a Quick Settings tile.
- **Privacy:** no telemetry. Biometric lock is on when the device supports it, and screenshot blocking is optional.

With background alerts off, Compy only stays connected while it's open, and the widget and tile show the last known state, with its age.

## Build

You need JDK 21, the Android SDK (platform 37, NDK 27.1, CMake 3.22) and Zig 0.17.

```sh
git clone --recurse-submodules <repository-url>
cd compy
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Gradle builds libghostty-vt from the pinned `third_party/ghostty` submodule. Debug builds also accept `http://` addresses, for a local test server:

```sh
rex server run --listen http://127.0.0.1:18765 --allow-anonymous
# In the emulator, connect to http://10.0.2.2:18765
```

`--allow-anonymous` gives every connection full access, so only use it on loopback.

## Program icons

Compy ships open icons from [Simple Icons](https://simpleicons.org), listed in `app/src/main/assets/icons/ATTRIBUTION.md`. To change the list, edit and run `scripts/generate-program-icons.py`.

## Releases

See [docs/RELEASING.md](docs/RELEASING.md).

## How it talks to Rex

Compy uses Rex's documented API, which any server serves at `/llms.txt`: a JSON control WebSocket at `/api/control`, and a binary terminal stream at `/api/data`. Each stream starts with a Ghostty snapshot, followed by raw program output. If a future Rex sends a snapshot Compy can't decode, Compy shows the current screen without history and says so.

## License

Apache-2.0. See [LICENSE](LICENSE). The bundled JetBrains Mono Nerd Font and Noto symbol fonts are under the SIL Open Font License; see `app/src/main/assets/fonts/`. libghostty-vt is MIT.
