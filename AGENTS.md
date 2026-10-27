# AGENTS.md: roadmap for an AI agent setting up Glass Home TV

You are helping a person set up this project on **their own** Sony BRAVIA TV and iPhone. Read `README.md` first for what the project is. This file is the order of work, the checks, and the rules.

The person may not be technical. Use short, plain sentences, one step at a time, and wait for them to confirm each step on the TV or phone. They do everything on the TV remote and the iPhone; you do the PC side.

## Safety rules (always)
1. **Everything must be reversible.**
   - Run `scripts/Backup-Tv.ps1` before the first change.
   - Disable apps, never uninstall them (`pm disable-user`).
   - Install only with `adb install -r`, which keeps data.
2. **Never type passwords, API keys or the Sony Pre-Shared Key for the person, and never sign in to anything.**
   - They type secrets themselves on the TV or phone.
   - Don't ask them to paste a secret into the chat.
   - If they do paste one, tell them to replace it.
3. **Never root, flash, factory-reset, or change system or network settings yourself.** Tell the person which setting to change and let them do it with the remote.
4. **Never touch these:**
   - live TV or tuner apps and inputs
   - the original launcher (`com.google.android.tvlauncher` or the Google TV launcher)
   - Google Play Services
   - the keyboard and input methods
   - anything you can't identify
5. **Download only from official sources.** Use the URLs in `scripts/Get-Tools.ps1` (Google and Microsoft) and apps' official GitHub releases. No unofficial APK sites, no pirate sites.
6. **Look before you press OK.** Before any key press that confirms something, check the screen (`Save-TvScreenshot` or `Read-TvScreen` in `scripts/TvTools.psm1`). Use real key presses so the person can watch.
7. **Respect the person watching TV.**
   - If an app other than Glass Home or the launcher is in front, ask before pressing anything.
   - Use `Get-TvFocus` to see which app is in front.
8. **Tell the truth about results.** If a check fails, say so and show the output.

## What each step needs from the person
| Needs | Who |
|---|---|
| Settings on the TV (Developer options, IP control, Accessibility, HomeKit) | The person, with the remote. You give exact menu paths. |
| Secrets (Sony PSK, OpenRouter key) | The person types them on the TV. |
| The iPhone Shortcut | The person builds it, following `docs/SHORTCUT.md`. |
| PC commands, building, installing, photos, tests | You. |

---

## Phase 0: check the hardware (stop if it doesn't fit)
Ask the person, or check once ADB works:
- **The TV is a Sony BRAVIA with Android TV / Google TV, Android 10 or newer.**
  - Check: `adb shell getprop ro.build.version.sdk` must be **29 or more**, and `ro.product.manufacturer` must be Sony.
  - Not a Sony: say clearly that only Glass Home and the photos can work, and voice key presses can't. Offer to continue with just those.
- **An iPhone with Siri and Shortcuts, on the same network as the TV.**
- **A Windows 10/11 PC on the same network.**
- **The network:**
  - The PC must be able to reach the TV. Guest Wi-Fi networks often block this.
  - Check: `Test-NetConnection <TV-IP> -Port 5555` after Phase 1.
- **OpenRouter:** the person needs an account at openrouter.ai with a little credit, and should set a monthly limit.

## Phase 1: connect the PC to the TV
1. The person turns on **Developer options** on the TV:
   1. Settings → Device Preferences → About.
   2. Select **Build** 7 times.
   3. Developer options → **USB debugging** → On. On Sony this also opens network ADB on port 5555.
2. The person finds the TV's IP address: Settings → Network & Internet → their Wi-Fi network.
3. You run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\Get-Tools.ps1
   $env:TV_IP = "<TV-IP>"
   powershell -ExecutionPolicy Bypass -File .\scripts\Connect-Tv.ps1
   ```
   - Before you download, tell the person that the Android files come under Google's SDK license.
   - The person selects **Always allow from this computer** → OK on the TV.
4. **Check:** `.\tools\platform-tools\adb.exe devices` shows `<TV-IP>:5555  device`.
5. Run `scripts\Backup-Tv.ps1` and keep the `backup\<date>\` folder it prints.

## Phase 2: Glass Home (home screen + photos)
1. **Personalise:**
   - `FAMILY` in `glasshome/src/com/ahmedfamily/glasshome/HomeActivity.java` is the name under the clock.
   - `FAVOURITES` in the same file are the dock apps. Use package names from `adb shell pm list packages`. Remove ones that aren't installed.
2. **Build:** run `powershell -ExecutionPolicy Bypass -File .\glasshome\build.ps1`.
   - The first build creates `glasshome/keystore/`. Never commit or share that folder: anyone with it can sign updates for this app.
   - Keep it, because later updates must be signed with the same key.
3. **Install:** `adb -s <TV-IP>:5555 install -r .\glasshome\out\GlassHome.apk`.
4. **The person turns on the Home-button helper:** Settings → Device Preferences → Accessibility → **Glass Home (Home button)** → Enable → OK.
5. **Check:** press Home (`Send-TvKey HOME`). `Get-TvFocus` should show `com.ahmedfamily.glasshome/.HomeActivity`; take a screenshot to confirm.
6. **Photos:**
   1. Have the person put their photos in `Family pictures\` in the repo folder.
   2. `Update-TvPhotos.ps1` skips identical files only. To catch look-alike copies (the same photo re-sent at a different size), compare small grey thumbnails (a difference hash). Show the person the pairs, and move extras to a backup folder rather than deleting them.
   3. Run `powershell -ExecutionPolicy Bypass -File .\scripts\Update-TvPhotos.ps1`.
   4. **Check:** it prints "Glass Home has taken the new photos". Press Home twice; the photo should change each time.

## Phase 3: voice control on the TV
1. **The person turns on Sony IP control:**
   1. Settings → Network & Internet → Home network → IP control.
   2. Authentication → **Normal and Pre-Shared Key**.
   3. Pre-Shared Key: a key **they choose**.
   4. Leave Simple IP control off.
2. **The person opens the Voice control screen** (microphone tile, last in the Glass Home dock) and:
   1. Types the same Pre-Shared Key in the IP-control box and saves it.
   2. Creates an OpenRouter key with a monthly limit and pastes it in the **OpenRouter AI key** box. The easiest way is the free Google TV app on their phone: connect it to the TV, then tap its keyboard icon. Then they press **Save AI key**.
   3. Notes the **code** shown at the top. This is the Shortcut's `X-Token`.
3. **Optional:** allow the screen picture, so "play the second one" works:
   `adb shell appops set com.ahmedfamily.glasshome PROJECT_MEDIA allow`. To undo: `... PROJECT_MEDIA default`.
4. **Optional:**
   - The Lionz TV, Shahid and TV Bro entries in `Actions.java` are examples measured on one TV.
   - If the person doesn't have those apps, remove them from `LIST`/`APPS` and from the prompt, and remove their cases from `scripts/Test-Voice.ps1`. Then rebuild.
   - For another app, measure its key routes on screen first. Don't guess.
5. **Check (presses nothing):** `.\scripts\Test-Voice.ps1 -Token <code>` runs every phrase in dry-run mode and should end with `0 failed`. With the AI key saved, add `-Live` to test the real AI.

## Phase 4: the iPhone Shortcut
1. Walk the person through `docs/SHORTCUT.md` one step at a time. Use `http://<TV-IP>:8765/talk` and their code.
2. **Check:**
   1. "Hey Siri, TV". Siri answers "Tell me".
   2. Say "turn the volume up a little". The TV banner shows it, and Siri says "Volume up."
   3. Say "thank you". The Shortcut stops.

## Phase 5: finish
- **Fixed IP:** the person asks whoever runs the router to reserve the TV's IP (DHCP reservation). Otherwise the Shortcut breaks when the address changes.
- **Optional HomeKit:** TV Settings → Apple AirPlay & HomeKit, then add the TV in the iPhone Home app. "Hey Siri, turn on the TV" then works while the TV still has network in standby.
- **ADB off:** the person turns ADB (USB) debugging off when PC work is done.
- **Leave the person a short note in their language:**
  - how to use it
  - how to change photos
  - how to undo
  - the limitations in README "Things to know"

## When something goes wrong
| Symptom | Likely cause |
|---|---|
| Siri: "Something went wrong" | The TV is off its network (deep standby or a new IP), or the iPhone is on another Wi-Fi. |
| "Wrong code." | The `X-Token` in the Shortcut doesn't match the Voice control screen. |
| Siri reads raw JSON | The Shortcut must take `say` with Get Dictionary Value. |
| Never stops | The If must be "Text **contains** Goodbye", with Stop inside it. |
| Home opens the old launcher | The accessibility helper is off (it can switch off after some updates). |
| Key presses don't work | IP control is off, or the Pre-Shared Key doesn't match the one on the Voice control screen. |
| YouTube opens on the iPhone | The person said "Siri" mid-conversation, so Siri took over. |

Live log while testing: `scripts\Monitor.ps1`. App log: `adb logcat -s GlassVoice`.

## Changing the code
- **Version:** bump `--version-code` in `glasshome/build.ps1` for every install. A lower version needs an uninstall, which deletes the photos.
- **Java:** 8 language level, no external libraries. Keep the style of the surrounding code.
- **New voice action:** add it to `Actions.LIST`, implement it in `Actions`, add a phrase to `scripts/Test-Voice.ps1`, and run the dry-run test.
