# 7UI

A home screen launcher for stock Android with a refined, gesture-driven feel.

## What works

- Home screen: multiple pages, 4x6 grid, 4-slot dock, no Discover feed.
- App drawer: horizontal pages (5x6) with free arrangement. Long-press and drag an icon
  anywhere; other icons reflow, a full page pushes its last icon to the next one.
- Drag to a page edge and hold to flip pages (or create a new one).
- From the drawer, drag an icon to the top bar ("Add to Home") and hold to jump to the
  home screen and drop it there.
- On home, drag an icon to the top "Remove" bar to remove the shortcut.
- Long-press without moving: Add to / Remove from Home, App info, Uninstall.
- Swipe up opens the drawer, swipe down pulls the notification shade.
- Search in the drawer, layout persisted in `layout.json`.

## Build

```bash
./gradlew assembleDebug
```

Install the APK, then pick 7UI in Settings > Apps > Default apps > Home app.

## License

PolyForm Noncommercial 1.0.0, see [LICENSE.md](LICENSE.md). Free for personal and other noncommercial use; commercial use needs separate permission.

## Privacy

7UI collects no data and has no internet access. See [PRIVACY.md](PRIVACY.md).
