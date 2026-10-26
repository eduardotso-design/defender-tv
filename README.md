# Defender TV

A Defender-style arcade shooter for Android TV / Google TV, playable with a gamepad.

## Features
- Full enemy roster: Landers, Mutants, Baiters, Bombers (lay Mines), Pods, Swarmers
- Humanoids to rescue; abducted ones become Mutants
- Smart bombs, hyperspace, direction flip
- Horizontally wrapping world with minimap
- Gentle difficulty curve (small +3% speed per wave, capped at 1.3x; enemy counts cap too)

## Controls (standard gamepad)
- Left stick / D-pad: fly
- A: fire (hold for continuous when rapid fire cheat is on)
- B: smart bomb (also: restart on game over)
- Y: hyperspace (3s cooldown)
- X: flip facing direction
- Start / Menu: pause

## Cheat codes (enter with gamepad buttons)
- Up Up Down Down Left Right Left Right B A — **Invincibility toggle**
- Up Up Up B B — **+10 smart bombs**
- Down Down A A — **+3 lives**
- Left Right Left Right Y — **Rapid fire toggle**
- A B A B — **Freeze enemies 10s**
- Y Y X X — **Skip wave**

## Build the APK (zero local setup)
1. Create a new GitHub repo and push this folder to it.
2. GitHub Actions runs automatically — see the **Build APK** workflow.
3. When it finishes, open the run, download the **DefenderTV-debug-apk** artifact, and extract `app-debug.apk`.

## Install on Google TV / Android TV
1. On the device: Settings → System → About → tap *Android TV OS build* 7 times to enable developer options.
2. Enable *Install unknown apps* for a file manager (e.g. "Send files to TV", "X-plore").
3. Copy `app-debug.apk` to the device (USB stick, LAN share, or Google Drive).
4. Open it with the file manager and install.
5. Launch "Defender TV" from the leanback home. Pair your gamepad over Bluetooth and play.

### Alternative: ADB install
```
adb connect <tv-ip>:5555
adb install app-debug.apk
```

## Build locally (optional)
Requires Android Studio or command-line SDK + JDK 17.
```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
