# Glass Home TV

Talk to your Sony BRAVIA TV through Siri, in plain English, and turn its home screen into a family photo frame.

- **"Hey Siri, TV"**: Siri says "Tell me" and keeps listening, so you can say "open YouTube and play relaxing piano music", "forward 30 seconds", "a bit louder", or "thank you" to finish.
- **Glass Home**: a new home screen for the TV. A family photo fills the screen, a different one each time you press Home, with a clock and a dock of glass-style app icons.

It is a small Android app (no Google Play, no Gradle) plus an iPhone Shortcut and a few PowerShell scripts. Built and used on one family TV; shared so others can do the same.

> **Using an AI agent to set this up?** Point it at [AGENTS.md](AGENTS.md). It is a step-by-step roadmap with checks and safety rules written for Claude, ChatGPT/Codex and other agents.

---

## What you need

### Minimum requirements

| | Required | Why |
|---|---|---|
| **TV** | **Sony BRAVIA with Android TV or Google TV, Android 10 or newer** | The app needs Android 10 (API 29). Voice key presses, typing and power use Sony's own **IP control** API. |
| | Developer options with **USB debugging** (on Sony this also allows ADB over the network) | The PC installs the app and photos over the network. |
| | **IP control** with a **Pre-Shared Key** (Settings → Network → Home network → IP control) | Lets the app press remote buttons, type text and turn the TV on/off. |
| | Wi-Fi or Ethernet on the **same network as the iPhone** | The iPhone talks to the TV directly on your home network. |
| | About 500 MB free storage | For the photos (about 500 photos). |
| **iPhone** | Any iPhone with **Siri** and the **Shortcuts** app, on the same Wi-Fi as the TV | The Shortcut uses only standard actions: Ask for Input, Get Contents of URL, Speak Text. No Apple Intelligence needed; the AI runs from the TV. |
| | Siri set to **English** | The AI instructions assume English speech (Arabic names are still searched in Arabic on YouTube). |
| **PC** | **Windows 10 or 11** with PowerShell (built in), about 1.5 GB free | Only for setup and for changing photos. The TV and iPhone work without it afterwards. |
| **Account** | An **OpenRouter** account with a little credit, at [openrouter.ai](https://openrouter.ai) | The TV sends your words to an AI model to choose what to do. Typical cost is a fraction of a cent per request. Set a monthly limit. |

### Tested on
- **TV:** Sony BRAVIA 4K UR2, Android TV 10, 32-bit ARM.
- **iPhone:** iPhone 15 Pro, iOS beta (October 2026).
- **PC:** Windows 11.

### Not tested / won't work
- **Non-Sony Android TVs:** Glass Home and the photos will probably work. Voice key presses, typing and power on/off will **not**, because they use Sony's IP control. Untested.
- **Samsung (Tizen), LG (webOS), Roku, Fire TV:** not Android TV, so no.
- **Mac or Linux PC:** the scripts are PowerShell and call Windows `.exe` tools. Porting is possible but not done.
- **Older iPhones:** the Shortcut actions have existed since iOS 13, but only the phone above was tested.

---

## How it works

```
iPhone                                  TV (Glass Home app)
"Hey Siri, TV"                           
  Shortcut asks "Tell me"               
  you speak  ──── POST /talk ──────────▶ port 8765, checks the X-Token code
                  (your words)            simple command? (volume, pause, back...) → do it, no AI
                                          otherwise → OpenRouter AI picks from a fixed list of actions
                                          runs them with Sony IP control on 127.0.0.1
  Siri speaks ◀── {"say": "..."} ──────── checks it really happened, answers in one sentence
  listens again (up to 20 times)
  stops when the answer contains "Goodbye"
```

- **Only a fixed list of actions can run:** remote keys, seek, open an app, TV on/off, and YouTube search and play. There are also optional example shortcuts for one IPTV app. The AI cannot buy, sign in or type passwords.
- **The OpenRouter key is kept on the TV, encrypted** with the Android Keystore. It is never in the Shortcut.
- **A small banner** at the top of the TV shows what it heard and what happened.
- **Glass Home** replaces the Home button through an accessibility service. Turning that service off brings the original launcher back.

## Quick start (by hand)

1. **TV:**
   1. Turn on Developer options and USB debugging.
   2. Turn on IP control with a Pre-Shared Key.
   3. Note the TV's IP address.
2. **PC:** clone this repo, then in PowerShell from the repo folder:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\Get-Tools.ps1     # downloads adb, JDK 17, Android build tools
   $env:TV_IP = "192.168.1.50"                                            # your TV's address
   powershell -ExecutionPolicy Bypass -File .\scripts\Connect-Tv.ps1     # accept "Allow debugging" on the TV
   powershell -ExecutionPolicy Bypass -File .\scripts\Backup-Tv.ps1      # snapshot + undo script, before any change
   powershell -ExecutionPolicy Bypass -File .\glasshome\build.ps1        # builds glasshome\out\GlassHome.apk
   .\tools\platform-tools\adb.exe -s "$($env:TV_IP):5555" install -r .\glasshome\out\GlassHome.apk
   ```
3. **TV:** Settings → Device Preferences → Accessibility → **Glass Home (Home button)** → On. Press Home.
4. **Photos:** put them in a folder named `Family pictures` in the repo folder, then:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\Update-TvPhotos.ps1
   ```
   Identical files are skipped (look-alike copies, such as a photo sent twice on WhatsApp, are not; remove those first). Tall photos get a soft blurred fill instead of being cropped.
5. **Voice:** open the microphone tile in the Glass Home dock (**Voice control**):
   1. Type the IP-control key.
   2. Paste your OpenRouter key. The easiest way is the Google TV app's keyboard on your phone.
   3. Write down the code shown there.
6. **iPhone:** build the Shortcut, about 15 minutes: [docs/SHORTCUT.md](docs/SHORTCUT.md).
7. **Test from the PC without pressing anything:**
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\Test-Voice.ps1 -Token <code>
   ```

Personalise before building:
- **Name under the clock:** `FAMILY` in `glasshome/src/.../HomeActivity.java`.
- **Dock apps:** `FAVOURITES` in the same file.
- **Apps voice can open:** `APPS` in `Actions.java`.

## Things to know
- **Waking the TV by voice** works only while the TV still has Wi-Fi in standby. The tested TV drops Wi-Fi about 20–35 minutes after it is turned off, so after that use the remote. An Ethernet cable may help.
- **Don't say "Siri" during the conversation.** Siri then handles the request itself, for example opening YouTube on the iPhone, and the conversation ends.
- **Local traffic is plain HTTP.** On a shared network someone could read the code and run the allowed actions. Use your own router if you can, and press **Make a new code** now and then.
- **The screen picture:** for requests like "play the second one", a small picture of the TV screen is sent to the AI.
- **Give the TV a fixed IP:** ask whoever runs your router for a DHCP reservation, otherwise the Shortcut breaks when the address changes.
- **Turn ADB debugging off** when you're done with the PC.

## Undo
- **Old home screen back:** Accessibility → Glass Home (Home button) → Off.
- **Remove the app:**
  ```powershell
  .\tools\platform-tools\adb.exe -s "$($env:TV_IP):5555" uninstall com.ahmedfamily.glasshome
  ```
  This also deletes its photos and settings.
- **Everything the scripts changed:** run `backup\<date>\undo.ps1 -DryRun`, then without `-DryRun`. `Backup-Tv.ps1` creates this script.

## Files
| Path | What |
|---|---|
| `glasshome/` | The Android app. `build.ps1` builds it with javac → d8 → aapt2 → apksigner and makes a fresh signing key on first build. |
| `glasshome/src/.../VoiceServer.java`, `Talk.java` | The `/talk` server and conversation logic. |
| `glasshome/src/.../Actions.java` | The allowed actions and the AI prompt. |
| `glasshome/src/.../FastPath.java` | Simple commands that skip the AI. |
| `glasshome/src/.../SonyApi.java` | Sony IP control: keys, typing, power. |
| `scripts/TvTools.psm1` | PC helpers: `Send-TvKey`, `Save-TvScreenshot`, `Get-TvFocus` and more. |
| `scripts/Monitor.ps1` | A live log of every command the PC sends. |
| `scripts/Disable-TvApps.ps1` | Optional: disable (never uninstall) ad and demo apps one at a time, checking Home after each. |
| `docs/SHORTCUT.md` | The iPhone Shortcut, step by step. |
| `AGENTS.md` | The roadmap for AI agents. |

## License
MIT. See [LICENSE](LICENSE). Not affiliated with Sony, Apple, Google or OpenRouter.
