# Running the website

Use Node.js **24.19.0** (see [`.nvmrc`](.nvmrc)) and npm **10 or newer**.

```sh
cd website
npm ci
npm run dev
```

The development server is available at `http://localhost:4321` and on your local network.

```sh
npm run build     # Build the static website into dist/
npm run preview   # Preview that build at http://localhost:4321
```

Run the checks with:

```sh
npm run lint
npm run format:check
npm run check
npm test
npm run test:browser
```

## License

Original website code and text use **GPL-3.0-or-later**, subject to existing material-specific
notices. See the root [`LICENSE`](../LICENSE), [`COPYING`](../COPYING) and
[`LICENSES`](../LICENSES/). Inherited content and third-party software, fonts and artwork retain
their applicable licenses, authorship and brand terms.

Distributed notices are in [`public/notices`](public/notices/), including
[third-party asset attributions](public/notices/third-party-assets.txt),
[Android artwork notices](public/notices/Android-artwork-notices.txt) and
[the GPL text](public/notices/GPL-3.0.txt).
