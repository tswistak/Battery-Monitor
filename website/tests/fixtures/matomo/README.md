# Real Matomo tracker fixture

`matomo.js` is the unmodified official Matomo **5.7.1** JavaScript tracker, used only by intercepted local browser tests. Its BSD-3-Clause license is retained in `LICENSE.txt`. `sources.json` records source URLs, byte counts and SHA-256 hashes.

The harness points at the reserved `analytics.invalid` hostname. Playwright fulfills the tracker script from this local fixture and fulfills tracking requests with a local response; tests must never contact the owner's production analytics instance. Production code loads the configured instance's tracker only after consent; this fixture is not shipped to visitors.

When updating it, fetch both files from the same official version, preserve the license, update the provenance hashes, and rerun the analytics browser suite against the actual request payloads and browser cookies.
