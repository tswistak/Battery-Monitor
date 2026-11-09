# Battery Monitor website

An independent, prerendered Astro website for Battery Monitor. It does not need Gradle, the Android
SDK or an emulator.

Use Node **24.19.0** (see `.nvmrc`) and npm 10 or newer:

```sh
cd website
npm ci
npm run dev
```

Development always imports the latest files from the current checkout. After dependency installation
it does not need GitHub access. `npm run build:production` instead resolves the latest published
stable app release and its immutable source commit. Both modes record app and website provenance
separately.

Website documentation lives in [`captures/docs/website`](../captures/docs/website/):

- [Development, builds and checks](../captures/docs/website/DEVELOPMENT.md)
- [Content import and compatibility](../captures/docs/website/CONTENT-IMPORT.md)
- [Accepted visual direction](../captures/docs/website/SELECTED-DIRECTION.md) and
  [design system](../captures/docs/website/DESIGN.md)
- [Configuration](../captures/docs/website/CONFIGURATION.md), [CI](../captures/docs/website/CI.md)
  and [Bunny deployment/rollback](../captures/docs/website/DEPLOYMENT.md)
- [Analytics](../captures/docs/website/ANALYTICS.md),
  [privacy operations](../captures/docs/website/PRIVACY-OPERATIONS.md) and
  [consolidated owner setup](../captures/docs/website/OWNER-SETUP.md)

Root `FAQ.md` was migrated once to website-owned FAQ content. It is not synchronized or used as an
importer input; its eventual removal and app-link changes remain with the owner.

## License

Original website code and original website text use **GPL-3.0-or-later**, subject to the
repository's existing material-specific notices. See [`LICENSE`](../LICENSE),
[`COPYING`](../COPYING) and [`LICENSES`](../LICENSES/). Inherited explanatory text and Android
artwork retain their applicable authorship and license terms.

Third-party software, Roboto fonts and provider badges retain their own licenses or brand terms. In
particular, Roboto uses SIL OFL 1.1; F-Droid artwork uses CC BY-SA 3.0; Octicons uses MIT; Liberapay
artwork uses CC0; Google Play and Suppi artwork remains subject to the provider terms recorded with
the assets. These assets are not relabeled as original GPL artwork.

Distributed notices are in [`public/notices`](public/notices/), including exact asset
origins/hashes. The deployed About page links the corresponding website and app-content revisions.
No change to the Android project's license or signing behavior is required.
