    Copyright 2013 Nils Assbeck, Guersel Ayaz and Michael Zoech
    
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
    
        http://www.apache.org/licenses/LICENSE-2.0
    
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## RemoteCam (new Android app)

`app/` is a new app on top of the original PTP protocol layer, now the `camera-core/` module
(`camera-core/src/main/java`; the legacy `res/` is reused for property icons).

Build: `gradle :app:assembleDebug` (JDK 17, AGP 9, `minSdk 28`). `androidCompileSdk` in
`gradle.properties` sets compile/target SDK (35 here; use 36 once that SDK is installed).
Create `local.properties` with `sdk.dir=...` if `ANDROID_HOME` is not set.

Changes: system USB permission with explicit `FLAG_IMMUTABLE` PendingIntent, receivers registered
with export flags, interface claiming, safe permission and detach handling, a bounded
synchronous bulk-transfer loop instead of the old `UsbRequest` pipeline, no ACRA/INTERNET/storage
permissions, adaptive phone/tablet UI, live view and shutter state feedback, JPEG preview export
through the system file picker. The session closes when the app leaves the foreground.

Not yet verified on real hardware: Canon/Nikon sessions, live view, capture and image transfer.

## Remote Your Cam OSS

This Android app allows you to control a Canon or Nikon DSLR camera through USB.
Since we no longer have time developing this app we decided to open source the code.
Maybe someone else wants to base their own app on this.

### Features

* Capture photos
* Review your taken photos
* Display current exposure mode
* Bulb shooting (Canon only)
* Live View
  * Histogram (Canon only)
  * Drive Focus
  * Adjustable duration of the captured picture in Live View mode
  * Zooming and panning in active Live View
* Gallery with fullscreen picture review, zooming and panning
* Picture stream showing captured pictures
* Control of the following properties:
  * White balance
  * Shutter speed
  * Iso sensitivity
  * Aperture priority
  * Color temperature
  * Auto focus metering mode (Nikon only)
  * Setting the focus point (Nikon only)
  * Setting the focus rectangle in live view (Nikon only)
  * Exposure Compensation

