# Build and install Podcini.C

## Export your existing library

In upstream Podcini.A, open **Settings → Import/Export → Combo export**. Select the database, downloaded media, and clips. Save the resulting `Podcini-Backups-…` folder in shared storage, outside the app's private directory. Keep the complete folder together.

## Build a release APK

Use JDK 21, Android SDK platform 37.1, and NDK 29.0.14206865. Set `ANDROID_HOME` to your Android SDK directory, or set `sdk.dir` in an untracked `local.properties` file. Use the repository's Gradle wrapper.

Release builds require your own signing key. Keep the key and its passwords outside the checkout and retain a secure backup: future updates need the same key.

For a first build on another machine, create a key (the command prompts for its password):

```sh
mkdir -p "$HOME/.android/podcini-c"
chmod 700 "$HOME/.android/podcini-c"
keytool -genkeypair -keystore "$HOME/.android/podcini-c/release.keystore" \
  -storetype PKCS12 -alias podcini-c -keyalg RSA -keysize 4096 \
  -validity 10000 -dname "CN=Podcini.C"
```

Create `$HOME/.android/podcini-c/release.properties` with these properties, using the absolute path to your key and its actual password:

```properties
releaseStoreFile=/absolute/path/to/.android/podcini-c/release.keystore
releaseStorePassword=YOUR_KEYSTORE_PASSWORD
releaseKeyAlias=podcini-c
releaseKeyPassword=YOUR_KEYSTORE_PASSWORD
```

Protect both files with `chmod 600`. When moving an existing installation's build to another machine, copy its signing files securely instead of generating a different key.

From the repository root, build with:

```sh
./gradlew :app:assembleFreeRelease \
  -PreleaseSigningPropertiesFile="$HOME/.android/podcini-c/release.properties"
```

The APKs are exported to `app/build/exported-apks/freeRelease/`. Choose the `Podcini.C-free-universal-<version>.apk` file. For casting support, use `:app:assemblePlayRelease` and the corresponding `playRelease` output directory. The Free variant does not include casting.

## Restore into Podcini.C

Install the release APK, then open **Settings → Backups & exports → Restore backup**. Select the exported `Podcini-Backups-…` folder, keep library/settings, downloaded media, and clips selected, and tap **Restore backup** once. The app restarts automatically when the restore is ready. Regrant access to local media folders if needed.

Check your subscriptions, listening progress, and downloaded playback before removing the old app. Podcini.A, Podcini.C Debug, and Podcini.C Release have separate app data. Installing Podcini.C Release does not import data from either of the other apps automatically.
