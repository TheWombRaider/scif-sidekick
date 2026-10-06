# Releasing

Releases are signed on the maintainer's own machine. The signing key never goes in the repository or in CI.

## One-time: create a signing key

Use a generic certificate name, since it is embedded in every APK:

```bash
keytool -genkeypair -v -keystore ~/scif-sidekick-release.jks -alias scifsidekick \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=SCIF Sidekick"
```

Back the keystore up somewhere safe. If it is lost, existing installs can't be updated in place. Then create `keystore.properties` in the repository root (it is gitignored):

```properties
storeFile=/path/to/scif-sidekick-release.jks
storePassword=...
keyAlias=scifsidekick
keyPassword=...
```

The same four values can instead come from the `SCIF_STORE_FILE`, `SCIF_STORE_PASSWORD`, `SCIF_KEY_ALIAS` and `SCIF_KEY_PASSWORD` environment variables.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, the version badge in `README.md`, the in-app changelog in `AboutScreen.kt`, and `CHANGELOG.md`.
2. Run the full check: `./gradlew testDebugUnitTest assembleDebug lintDebug`, and `scripts/verify_no_history_queries.sh`.
3. Build: `./gradlew assembleRelease`. The signed APK is `app/build/outputs/apk/release/app-release.apk`.
4. Install it on a test device and check the main screens and a forwarded message before publishing.
5. Write the checksum: `sha256sum app-release.apk > app-release.apk.sha256`.
6. Create a GitHub release for the tag, attach the APK and the `.sha256` file, and paste the changelog entry.

Users verify with `sha256sum -c app-release.apk.sha256`.
