# Releasing

## Share a test build

The QA build is shrunk like a release and signed with the local debug key. Leave out local Rex icons when it will be uploaded anywhere:

```sh
./gradlew :app:assembleQa -PnoLocalIcons
```

## Sign a release APK

1. Create a keystore once, and keep it and its passwords backed up. Updates must be signed with the same key.

   ```sh
   keytool -genkeypair -v -keystore compy-release.jks -alias compy -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add a `keystore.properties` file to the repository root. It's ignored by Git.

   ```properties
   storeFile=compy-release.jks
   storePassword=...
   keyAlias=compy
   keyPassword=...
   ```

3. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

4. Build it.

   ```sh
   ./gradlew :app:assembleRelease
   ```

   The APK is at `app/build/outputs/apk/release/app-release.apk`.

5. Tag the commit and attach the APK to a GitHub release. People can then install and update it with [Obtainium](https://obtainium.imranr.dev).

## Publish a self-hosted F-Droid repository

Run [fdroidserver](https://f-droid.org/docs/Setup_an_F-Droid_App_Repo/) in a separate folder, because its keystore is another secret to back up.

```sh
fdroid init
cp /path/to/app-release.apk repo/
fdroid update --create-metadata
fdroid deploy
```

Host the generated `repo/` folder on any static web host, such as GitHub Pages, and share its URL.

## Main F-Droid repository

Not submitted yet. Its builders would need Zig 0.17 to compile libghostty-vt from the submodule.
