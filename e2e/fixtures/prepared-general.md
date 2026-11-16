`prepared-general.zip` is the deterministic input for cancelled, selective, and
partially failed general imports. It reuses the pinned
`historical-1.7.3/historical-1.7.3-general.zip` archive, changing only
`settings.json` values `autostart` to `never` and `enable_logging` to `false`.
This keeps import setup from starting monitoring or adding history readings.
Other archive entries are unchanged; this fixture does not establish export correctness.
`general-backup-round-trip.e2e.ts` continues to test the application's actual export.

SHA-256: `5d887ce88c1ba5e1427ac413887bec9b16fd28cd35c42b1b3803213ac4a69d8b`.
