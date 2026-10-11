# GitHub builds and automatic Play releases

Workflow: `.github/workflows/android.yml`.

Every pushed commit and pull request runs the debug build, MIDI unit tests, lint, instrumentation APK compilation, release bundle build, and 16KB APK alignment check. APKs, unsigned AABs, and reports are retained as GitHub Actions artifacts for 14 days. Instrumentation APK compilation is not an on-device test run; run the device checks in RELEASE.md before promoting a release.

A local commit does not trigger GitHub Actions. Push it to GitHub first.

## Enable automatic internal testing releases

1. In repository Settings → Environments, create `play-internal`. Restrict its deployment branch to `master`. You can add required reviewers if you want approval before an internal release.
2. Add these secrets to that environment:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 contents of the existing Play upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Existing upload key alias |
| `ANDROID_KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Raw service-account JSON with release access for `com.flaze` |

Use the existing upload key for this app, not a newly generated key. Do not commit keys, passwords, or service-account files.

3. Enable the Google Play Android Developer API for the service account's Cloud project. Invite its email in Play Console → Users and permissions and grant access to this app and testing-track releases.
4. Set repository Actions variable `PLAY_AUTO_RELEASE` to `true` after the credentials and internal track are ready.
5. Push to `master`. The release job runs only after all verification succeeds and uploads a signed AAB to **internal testing**. Other branches and pull requests only build/test.

CI releases use version code `1000 + github.run_number`; local builds use 13. Ensure that the result is higher than any version already uploaded to Play. After enabling CI publishing, use CI-generated bundles for subsequent releases or explicitly set `PLAY_VERSION_CODE` to a larger value for local uploads. Rerunning an already uploaded release with the same run number does not create another Play version; make a new push for a new version.

Set `PLAY_AUTO_RELEASE` to `false` to stop automatic uploads while retaining CI checks. This workflow does not automatically roll out to production. Promote a tested internal release in Play Console when ready; a production workflow can be added with a separate protected environment and rollout policy.

The repository must be pushed before any workflow runs. Credentials, GitHub environment configuration, and the Play track cannot be validated from the local workflow file alone.

References: [GitHub Gradle CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle), [Play upload action and service-account setup](https://github.com/r0adkll/upload-google-play).
