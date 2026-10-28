# Open Android Doc Scanner (v0.1.0-rc10)

Vendored from https://github.com/phillip9933/open-android-doc-scanner at tag
`v0.1.0-rc10` (commit `d87e4ed055a01a915ed318afdc1b6c42a9ab62ac`).
The original project is Apache-2.0 licensed. See `LICENSE`, `NOTICE`,
`THIRD-PARTY-NOTICES.md`, and the notices packaged with the native libraries.

Local integration changes:

- Android library modules compile against SDK 35, matching Homework Buddy;
  the prebuilt native libraries and DocQuadNet model are unchanged.
- `ScannerFlow` accepts a default export format and a PDF visibility switch.
  Homework Buddy uses one page and JPEG output.
- Upstream `scanner-sample` and Android instrumentation tests are excluded
  from the vendored runtime modules.

The app uses the complete upstream scanner flow with AI detection. Keep the
five SDK modules on the same upstream revision when updating them.
