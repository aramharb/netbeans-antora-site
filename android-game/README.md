# Gem Pop – Android match-3 (Godot 4)

Polished portrait match-3 puzzle game. All art is drawn in code (no image
assets), so it stays sharp on every screen density.

## Features
- Swipe or tap-tap to swap gems; invalid swaps bounce back
- Cascades with combo multiplier, particle bursts, bouncy gravity animation
- Distinct shape per colour (colour-blind friendly)
- Haptic feedback, saved best score, auto-reshuffle when no moves remain
- MSAA 2D, Mobile renderer, ETC2/ASTC compression, 720x1280 scaled layout

## Run
1. Install Godot 4.3+ and open this folder (`project.godot`).
2. Press F5 (use the 360x640 window; mouse emulates touch).

## Build for Android
1. Editor → Manage Export Templates → install; Editor Settings → Export → Android: set SDK and JDK paths.
2. Project → Export → Android → set your keystore, change `package/unique_name`.
3. Export APK/AAB (or enable "Run on remote device" over USB).

## Ideas next
Sound effects, special gems (4/5 matches), levels with goals, safe-area insets.
