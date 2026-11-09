<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" height="128" alt="Compy's icon: a smiling purple and orange dinosaur">
</p>

<h1 align="center">Compy</h1>

<p align="center">An unofficial, native Android client for Rex terminal servers, with extras for running coding agents from your phone.</p>

Kotlin and Jetpack Compose on Android 14 and up, for phones, foldables and tablets. Not affiliated with the makers of Rex.

## Connect to a server

Compy reaches servers over Tailscale, so install the Tailscale app on your phone and join the same tailnet. Then, on the computer running Rex:

```sh
rex server tailscale on
rex server tailscale pair
```

In Compy, tap **Scan pairing code** and scan what it shows, or type the `ts.net` address. Release builds only use HTTPS.

## Beyond a Rex client

Things Compy does on top of showing your Rex sessions:

- **Agent chat:** tap the bubble in the corner of a Claude Code pane to read its conversation as a chat instead of a terminal. Replies are formatted, tool calls fold into one line each with a ✓ or ✗, and you can type a message back. Needs a one-time setup on the host ([below](#set-up-agent-chat)).
- **Agent images:** images an agent reads show as thumbnails in the chat. Tap one to see it full screen, or open the gallery to see every image in the conversation.
- **Agent alerts:** Compy stays connected in the background and notifies you when an agent needs you, finishes or fails. Open the pane from the notification, or reply to the agent without opening Compy.
- **Activity view:** every pane on every server, grouped by what needs you.
- **Send files to your computer:** share files to Compy from any app, or use **Upload files…** in a pane's menu. They're copied to that pane's computer and their paths are typed into the terminal, ready to hand to an agent. Needs Rex with API revision 11 or later on the host.
- **Share text into a pane:** share text from any app and pick the pane to paste it into.
- **Home screen and Quick Settings:** a widget and a tile show what your agents are doing, and app shortcuts jump straight back into a recent session.
- **Phone-friendly typing:** a key bar you can customize, sticky modifiers, a composer with voice typing, and hardware keyboard and mouse support.
- **Fits your screen:** each session either sizes its terminals to your phone while Compy is open, or keeps another device's size and lets you pan and zoom.
- **Many servers:** all your Rex servers in one list, reached over Tailscale.
- **Your look:** the app takes its colors from the host's terminal theme or from your wallpaper, and Settings offers six app icons.
- **Privacy:** no telemetry, a biometric lock when the device supports it, and optional screenshot blocking.

With background alerts off, Compy only stays connected while it's open, and the widget and tile show the last known state, with its age.

## Rex, on your phone

Everything you'd expect from a Rex client:

- **Terminals:** live panes with scrollback, drawn by libghostty-vt.
- **Layout:** Rex's real split layout, with draggable dividers and an arrange mode. These edits change the layout for every connected client.
- **Panes:** press empty space and drag the mini window onto a pane to split it where the highlight shows. Drag a pane by its corner handle to move or swap it, or into the trash can to close it. Float panes with a bar to drag, a corner to resize and a tray for minimized ones, or detach them to keep them running outside any window.
- **Commands:** tap `⌘` for a searchable palette of Compy's commands and the host's Rex actions; hold it to latch the modifier.
- **Terminal tools:** tap links, find in scrollback, and jump between prompts or copy a command's output when the shell marks them with OSC 133.
- **Status:** program icons, agent status dots, progress rings, the current folder under each window, and a haptic on the terminal bell.
- **Android:** predictive back, and layouts for phones, foldables and tablets.

## Set up agent chat

Rex doesn't share agents' conversations with clients, so Compy reads them through a small action you add to the host's Rex config. On the computer running Rex, copy [`rex/compy.lua`](rex/compy.lua) next to your `init.lua` and load it from there:

```sh
cp rex/compy.lua ~/.config/rex/compy.lua
echo 'dofile(os.getenv("HOME") .. "/.config/rex/compy.lua")' >> ~/.config/rex/init.lua
rex config reload
```

The actions find the transcript of the agent running in a pane (Claude Code for now) and return part of it, or one of its images. They read nothing else: the pane's agent decides the file, and callers can't name one.

When a Compy release changes `compy.lua`, copy it again and run `rex config reload`. Images need the version from Compy 0.2.9 or later.

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
