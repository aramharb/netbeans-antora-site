# Gem Pop – Android Studio project (Kotlin)

Match-3 puzzle where every tile has a **color** and a **number**.

| Match (3+ in a row/column) | Result |
|---|---|
| Same **color** | normal match |
| Same **number** | normal match |
| Same **color AND number** | **PERFECT**: clears the whole row + column, **+2 moves**, +300 bonus |

### Special gems
| Create by | Gem | Effect |
|---|---|---|
| Matching exactly **4** | **Bomb** (white spikes + spark) | when matched, explodes a 3x3 area (bombs chain) |
| Matching **5+** | **Rainbow** (spinning colors) | swap it with any tile to clear every tile of that color; two rainbows clear the board |

### Sound
Effects (swap, invalid, matches that rise in pitch with combos, perfect, boom, rainbow, game over) are
synthesized in `SoundFx.kt`, so there are no audio files. Toggle with the SOUND button (saved).

Swipe a tile, or tap two neighbouring tiles, to swap. Cascades raise a combo
multiplier. 25 moves per game; best score is saved.

## Open & run in Android Studio
1. **File → Open…** and choose this `android-game` folder (Android Studio Koala or newer; JDK 17 is bundled).
2. Let Gradle sync finish (it downloads Gradle 8.7 + Android Gradle Plugin 8.5.2; SDK 34 will be offered if missing).
3. Pick an emulator or a USB device and press **Run ▶**.

CLI: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

No third-party dependencies; all graphics are drawn on a `Canvas` in `GameView.kt`.
