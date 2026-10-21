# Play Store assets

Everything the Play Console store listing asks for, ready to upload or paste.

| Play Console field | File |
|---|---|
| App name | [text/title.txt](text/title.txt) |
| Short description (80 max) | [text/short-description.txt](text/short-description.txt) |
| Full description (4000 max) | [text/full-description.txt](text/full-description.txt) |
| Release notes ("What's new") | [text/release-notes.txt](text/release-notes.txt) |
| Category, tags, contact, privacy policy | [text/contact.txt](text/contact.txt) |
| App icon (512 × 512) | [graphics/icon-512.png](graphics/icon-512.png) |
| Feature graphic (1024 × 500) | [graphics/feature-graphic.png](graphics/feature-graphic.png) |
| Phone screenshots (1080 × 1920) | [screenshots/](screenshots/) |

Upload the screenshots in file-name order. Play needs at least 2 and takes up
to 8.

## Regenerating

- **Icon and feature graphic** are drawn from the launcher icon's shapes and
  the city's street map:
  `python3 tools/store/make_graphics.py --out store/assets/graphics`
- **Screenshots** come from the real app on an Android emulator: run the
  **Store screenshots** workflow from the Actions tab. It drives the test build
  through set scenes (simulated car, debug controls hidden) and commits the
  images here.

Form answers for Policy → App content are in
[../play-console-answers.md](../play-console-answers.md).
