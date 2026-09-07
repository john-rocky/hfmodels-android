# Cutting an SDK release

1. `HfModelsVersion.SDK_VERSION` in `core/src/main/kotlin/io/github/johnrocky/hfmodels/Api.kt` = the release version (it is what `PreparedModelInfo.sdkVersion` reports; 0.1.0 shipped reporting `0.1.0-SNAPSHOT`).
2. The Gradle line in `README.md`, `AGENTS.md`, `llms.txt`, `docs/api.md` and `skills/hfmodels-android/SKILL.md` = the same version.
3. `./gradlew :core:testDebugUnitTest` and the device tests (`litertlm/src/androidTest`, `tools/gate.sh` on the bundled catalog) on the runtime pinned in `gradle.properties`; a new runtime pin needs a new row in `tested-runtime-matrix.json` with its log.
4. `./gradlew publishAndReleaseToMavenCentral -PhfmodelsVersion=<version>` (credentials and the signing key come from `~/.gradle/gradle.properties`, never from the repository).
5. Tag `v<version>` only after `https://repo1.maven.org/maven2/io/github/john-rocky/hfmodels/hfmodels-litertlm/<version>/` answers 200.
6. Set `SDK_VERSION` to `<next>-SNAPSHOT`.
