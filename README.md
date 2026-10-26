# Issue Mortem

Draw on a screenshot of your computer with a pen, then turn it into a GitHub issue.

Issue Mortem is an Android app (made for tablets with a pen, works on phones too). It grabs your computer's screen,
lets you mark it up, and files a detailed GitHub issue from your tablet: a title, labels, your note, numbered markers
with their own notes, and the marked-up screenshot right in the issue.

- **Capture** the screen of any of your computers (Linux, Windows or Mac). Pick which one when you have several.
- **Mark it up**: pen with pressure, highlighter, arrows, numbered markers with a note each, shapes that snap
  straight when you hold the pen still, lasso to move things, eraser, zoom, grid, undo/redo.
- **Issue**: pick a repo and labels, give it a title, and the issue is made with everything above.
- **Send / Save**: drop the picture and note into a folder on the computer instead (`AI Inbox` or `Saved Notes`),
  handy for handing a screenshot to an AI coding assistant.
- **History**: every note stays on the device and can be reopened and edited.

It has two parts:

| Part | Runs on | Does |
|---|---|---|
| **Issue Mortem** app | your Android tablet or phone | drawing, notes, GitHub issues |
| **Issue Mortem helper** | each computer you want screenshots from | takes the screenshot, saves notes you Send |

The app and the helper talk over your **home network** (the same Wi-Fi or router). To use it away from home, use
[Tailscale](https://tailscale.com), a free private network between your own devices, or
[your own server](#your-own-server-relay) running the small Issue Mortem relay.

---

## Setup

### 1. Network

**At home**: nothing to set up. Keep the tablet and the computers on the same home network (Wi-Fi or router).

**Away from home (optional)**: without Tailscale it works on your home network only. To use it anywhere, use
Tailscale, or [your own server](#your-own-server-relay) with the Issue Mortem relay. Tailscale, once on every device:

1. Make a free account at [tailscale.com](https://tailscale.com).
2. Install Tailscale on your **tablet** (Play Store) and on **each computer**
   ([downloads](https://tailscale.com/download)), and sign in to the same account on all of them.
3. Check the devices show up at [login.tailscale.com/admin/machines](https://login.tailscale.com/admin/machines).
   Each one gets an address starting with `100.`. That's the address that works from anywhere.

### 2. The helper (on each computer)

Download the installer for your computer from the [Releases](../../releases) page and open it:

| Computer | Installer |
|---|---|
| Windows 10 / 11 | `issue-mortem-helper-<version>-windows-x64.msi` |
| Mac (Apple chip or Intel) | `issue-mortem-helper-<version>-macos.pkg` |
| Ubuntu, Debian, Mint, Pop!_OS | `issue-mortem-helper_<version>_amd64.deb` |
| Fedora, openSUSE | `issue-mortem-helper-<version>-1.x86_64.rpm` |
| Arch, Manjaro, EndeavourOS | `issue-mortem-helper-<version>-1-x86_64.pkg.tar.zst` |

(Raspberry Pi and other ARM Linux: the `arm64` / `aarch64` files.)

When the install finishes, the helper's window opens. It shows this computer's name, address and key, each
with a **Copy** button, and an **Open** button for the folder where notes you Send or Save land (`~/Issue Mortem`).
It opens as an app window in Chrome, Edge, Chromium or Brave if you have one, otherwise in your default browser, and
only this computer can open it.

The helper then runs in the background and starts by itself at every login. To see the window again, open
**Issue Mortem Helper** from the Start menu (Windows), the Applications folder (Mac) or the app menu (Linux).

Per system:

- **Windows**: if SmartScreen warns, choose *More info > Run anyway*. The installer lets only your home network
  (local subnet) and Tailscale addresses (`100.64.0.0/10`) through the firewall. Uninstall from *Settings > Apps*.
- **Mac**: if macOS asks whether Issue Mortem Helper may accept incoming network connections, allow it. The installer isn't signed by Apple yet, so the first time, right-click it and choose *Open* (or allow it in
  System Settings > Privacy & Security). The first screenshot makes macOS ask for **Screen Recording** permission for
  Issue Mortem Helper: allow it, then open Issue Mortem Helper once more. It captures the main display. To uninstall,
  run `launchctl bootout gui/$(id -u)/app.issuemortem.helper; sudo rm -rf "/Applications/Issue Mortem Helper.app" /Library/LaunchAgents/app.issuemortem.helper.plist`.
- **Linux**: install with your package manager, for example `sudo apt install ./issue-mortem-helper_*_amd64.deb`,
  `sudo dnf install ./issue-mortem-helper-*.rpm` or `sudo pacman -U issue-mortem-helper-*.pkg.tar.zst`. Then open
  **Issue Mortem Helper** from the app menu (it starts at every login from then on). Screenshots on Wayland need
  `grim` (Sway, Hyprland and other wlroots desktops), `spectacle` (KDE) or `gnome-screenshot` (GNOME); X11 needs
  nothing extra. The key window uses `zenity` or `kdialog`.

The key lives in your user's config folder (`issue-mortem/key`) and the log in its cache folder
(`issue-mortem/helper.log`). Running the helper by hand: `issue-mortem-helper` serves and prints the same text;
options are `-port 8642`, `-folder <path>` (where sent notes go, default `~/Issue Mortem`) and `-new-key` (make a new
key; the app then needs the new one).

### 3. The app (on your tablet)

1. Download `Issue Mortem.apk` from [Releases](../../releases) on the tablet and open it. Android asks to allow
   installing from your browser or Files app: allow it.
2. Open **Settings** and tap **Sync**. For a minute the app looks for computers, and every computer with the helper
   running finds it and adds itself (name, address and key). Nothing to do on the computers. Each one then shows
   *Online* in Settings.
   - On the home network, Sync needs the tablet and the computer on the same network. A computer found this way works
     at home only; the app says so.
   - Over Tailscale, Sync needs Tailscale on, on both, and signed in to the **same Tailscale account**: helpers only
     offer themselves to Android devices of their own account.
   - Or tap **Add computer** and type the name, address and key from the helper's window.

   Add as many computers as you like. With more than one, the start page shows **From** so you can pick where the
   screenshot comes from.

### Your own server (relay)

If you have a server with a domain (a VPS, or a home server reachable from the internet), the Issue Mortem relay lets
the app reach your computers from anywhere, without Tailscale and without opening anything on your home router. Each
helper dials out to the relay; the app talks to the relay over HTTPS.

**On the server**, run the relay behind HTTPS, with a long password of your own as `RELAY_SECRET`:

```sh
docker build -t issue-mortem-relay https://github.com/GTKottman/issue-mortem.git#main:relay
docker run -d --name issue-mortem-relay --restart unless-stopped -e RELAY_SECRET='a long password' \
  -p 127.0.0.1:8080:8080 issue-mortem-relay
```

Then point your HTTPS proxy at `127.0.0.1:8080` for a name like `relay.example.com`. With Caddy that's one line in
the Caddyfile: `relay.example.com { reverse_proxy 127.0.0.1:8080 }`. With Coolify or another Docker panel, add
`relay/` from this repo as a Dockerfile app on port 8080 with the `RELAY_SECRET` variable and a domain. Opening
`https://relay.example.com` should say *Issue Mortem relay is running.*

**On each computer**, open the helper's window, and under **Away from home: your own server** type the relay's
address and password, then **Save**. It shows *Connected* and an **Away** address like
`https://relay.example.com/c/9b857805b44eb09c83839ffb`.

**In the app**, tap **Add computer** and type that Away address with the computer's key. It works anywhere the tablet
has internet. (At home you can keep the home-network entry too and pick either under **From**.)

- Only helpers with the relay password can connect, and the app's calls still need each computer's key.
- The relay sees the traffic (keys and screenshots) in the clear inside the server, so run it only on a server you
  trust. Always put it behind HTTPS.
- Away addresses come from the key: after `-new-key` the computer gets a new Away address.

### 4. GitHub (once)

The app makes issues with your own GitHub token. It never leaves the device except to talk to GitHub.

1. In the app, tap **Settings > Make a token** (or open
   [github.com/settings/personal-access-tokens/new](https://github.com/settings/personal-access-tokens/new)).
2. Give it a name like `Issue Mortem` and an expiry.
3. **Repository access**: *All repositories*, or *Only select repositories* with the ones you want issues in.
4. **Repository permissions**: set **Contents** to *Read and write* and **Issues** to *Read and write*.
   (Metadata: Read-only is added by itself.)
5. **Generate token**, copy it, paste it into the app's GitHub box and tap **Save and check**. It should say
   *Signed in as* your username.

A classic token with the `repo` scope works too.

---

## Making an issue

1. Open the app: it captures the chosen computer's screen straight away (or tap **Capture screen**).
2. Mark it up. Use **Marker** for numbered spots: each one gets its own note.
3. Write what's wrong in the note box. Its first line becomes the issue title.
4. Tap **Issue**, pick the repo and labels, check the title, **Create issue**. Tap **Open** to see it on GitHub.

The issue looks like this:

```markdown
Login button overlaps the logo on small screens.

### Markers

1. The logo gets cut off here
2. Button should sit below the header

![Screenshot with markup](https://github.com/you/repo/blob/issue-images/issue-mortem/2026-10-25_14.30.12.png?raw=true)
```

### Where the screenshots go

GitHub has no way for apps to attach a picture to an issue, so Issue Mortem commits each picture to a branch called
**`issue-images`** in the same repo, under `issue-mortem/`, and the issue links to it. The branch is made on first
use and holds only these pictures (it shares no history with your code), so it never gets in the way. Don't merge it.
In a private repo the pictures stay private: only people who can see the repo can see them.

---

## Security

- The helper only answers requests that carry its key, and waits a second after a wrong one.
- Sync over Tailscale: every few seconds the helper asks `tailscale status` for the online Android devices of its own
  Tailscale account and offers them its name, address and key.
- Sync on the home network: while syncing, the app broadcasts a hello on the network, and the helper offers its name,
  address and key to any home-network device that sends one. So **anyone on your network can get the key** by sending
  that hello. Fine on your own home network; on a shared network (office, dorm, café) use Tailscale and add the
  computer by hand.
- A device only takes an offer during the minute after you tap **Sync**, and only from a Tailscale or home-network
  address.
- The helper's window is served only to the computer itself (`http://127.0.0.1:8642/`).
- It listens on every network of the computer. On the home network the key and screenshots travel unencrypted, so
  anyone on that network could read them. Tailscale encrypts the traffic.
- Anyone with the key can see your screen. Keep it private; `-new-key` replaces it.
- The GitHub token is stored in the app's private storage on your device.

## Build from source

**App** (no Gradle; plain Android SDK tools): needs JDK 17 and an Android SDK with `build-tools;35.0.0` and
`platforms;android-35`.

```sh
cd app
ANDROID_SDK=/path/to/android-sdk ./build.sh        # signs with your debug key
KEYSTORE=release.jks KEYSTORE_PASS=... KEY_ALIAS=... ANDROID_SDK=... ./build.sh   # release key
```

**Helper and its installers** (Go 1.22+):

```sh
cd helper && go test ./... && cd ..
packaging/build-linux.sh          # .deb, .rpm, Arch; needs nfpm (go install github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.43.0)
packaging/build-macos.sh          # .pkg; on a Mac
packaging/build-windows.ps1       # .msi; on Windows with WiX 5 (see the top of the script)
```

Pushing a tag like `v1.0.0` builds all of them plus the app on GitHub Actions and attaches them to a release
(`.github/workflows/release.yml`). To sign the app with your own key there, add the repo secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASS` and `KEY_ALIAS`.

The helper's API, if you want to write your own: `GET /api/hello`, `GET /api/screen` (PNG) and
`PUT /api/file?path=/folder/name.ext`, each with the header `X-Key: <key>`. Sync: while syncing, the app listens on
port 8642 for `POST /sync` with `{"name", "address", "key"}`, and broadcasts the UDP text `issue-mortem-sync` to
port 8642 every 2 seconds; the helper listens for it on UDP 8642.

The helper's window uses Barlow Condensed (SIL Open Font License; copies in `helper/web/OFL.txt` and `app/FONT-OFL.txt`); the app ships the same font.
