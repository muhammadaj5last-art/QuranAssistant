# Local dependency and asset setup

Restore these from your existing working Android Studio project. Do not commit private keys, local SDK paths or generated build folders.

| Destination | Required local content |
| --- | --- |
| `app/libs/sherpa-onnx-2.0.1.aar` | Existing sherpa-onnx Android library |
| `app/src/main/assets/models/ar/` | `encoder_model.ort`, `decoder_model_merged.ort`, `tokens.txt` for Arabic |
| `app/src/main/assets/models/en/` | Same three filenames for English |
| `app/src/main/res/raw/` | `surah_001.mp3`, `surah_002.mp3`, `surah_112.mp3` |

The older English keyword test screen also needs the original `assets/models/kws/` directory if you choose to run it. The main Arabic assistant uses the Arabic speech model.

The three recordings are required by the current source's `R.raw` references. This source-only checkout will not build until they are restored. The app's existing bundled-audio availability checks also assume those recordings exist.

Do not substitute arbitrary model files merely by renaming them: they must match the recognizer configuration. Exact public acquisition links remain to be verified. The original owner's local project already contains the working files.

## Uploaded speech binary identity

Filename: `sherpa-onnx-2.0.1.aar`

Size: 49441341 bytes

SHA-256: `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`

This checksum identifies the supplied binary; it does not establish upstream provenance or licensing.

## Before the first public APK release

- Verify the speech binary, models and recording redistribution terms and include their required notices.
- Restore the working assets and build on the owner's Android Studio installation.
- Test wake activation, updated parser commands, sleep, download/playback and airplane-mode playback on the phone.
- Generate a signed APK; never publish the signing key or passwords.
