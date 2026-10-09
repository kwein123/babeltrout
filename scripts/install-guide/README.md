# Install guides

`make_guide.js` (English) and `make_guide_fa.js` (Farsi, right-to-left) build the one-page Word guides in
`docs/`. Edit the text in these scripts, then rebuild both:

```bash
cd scripts/install-guide && npm install && npm run build
```

Update the sizes when they change (app APK, translation models, voice packages in `PiperVoiceCatalog.kt`).
The scripts use the [docx](https://github.com/dolanmiu/docx) npm package by Dolan Miu (MIT).
