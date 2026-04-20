This is a Kotlin Multiplatform project targeting Android, iOS, Server.

* [/mobile/app](./mobile/app/src) — `:mobile:app`, the KMP + Compose Multiplatform app (Android + iOS).
  - [commonMain](./mobile/app/src/commonMain/kotlin) holds shared UI code.
  - Platform-specific folders (`androidMain`, `iosMain`) hold per-target code.

* [/iosApp](./iosApp/iosApp) is the Xcode project entry point for iOS, consuming the `ComposeApp` framework
  emitted by `:mobile:app`. Add SwiftUI host code here.

* [/backend/ktor](./backend/ktor/src/main/kotlin) — `:backend:ktor`, the Ktor server application.

* [/core/domain](./core/domain) — `:core:domain`, pure Kotlin/JVM, zero vendor dependencies.
* [/core/data](./core/data) — `:core:data`, pure Kotlin/JVM, interfaces and DTOs.

* [/shared/tmp](./shared/tmp) — `:shared:tmp`, scratch placeholder for KMP boilerplate.
  Will be split into real `:shared:<name>` modules as features are built.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :mobile:app:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :mobile:app:assembleDebug
  ```

### Build and Run Server

To build and run the development version of the server, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :backend:ktor:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :backend:ktor:run
  ```

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…