# Contributing

Thanks for helping. BedPlay is a small project; the most useful contributions are:

- **Benchmarks from other hardware.** Run `tools/measure.py` while a stream is playing and open an issue with the output, your stick model, Wi-Fi band and console model.
- **Standby wake-up and cloud mode tests.** Both are implemented but only lightly tested (see "Known limitations" in the README).
- **Translations.** The UI strings live in the `EN` table at the top of `android/app/src/main/assets/app.js` (the Italian text is the key). Add a table for your language and a locale check.
- **A hardened release build** (signed, DevTools off).

## Ground rules

- Keep the app light: it targets 1.5-2 GB sticks. No big dependencies.
- Never commit tokens, logs containing tokens, console names or personal IP addresses.
- Please do not add Microsoft logos or Xbox branding to the app, icons or in screenshots.
- Do not use this project to bypass paid services or access content you do not own. Issues and pull requests that do will be closed.

## Building

See the README. To change the player, edit `web/build.js` and run `npm run build` in `web/`. The generated `xCloudPlayer.min.js` is committed so that the Android build does not need Node.

## Pull requests

Keep them focused. Describe what you tested and on which device.
