# VEIKK Reader (Android)

Reads raw pen data from a VEIKK drawing tablet plugged into your phone over USB (OTG).

## Build
Open this folder in Android Studio (it will create the Gradle wrapper and fetch the Android SDK itself), then press Run with your phone attached in USB-debugging mode. Or from the command line with the Android SDK installed: `gradle assembleDebug`. The APK ends up in app/build/outputs/apk/debug/app-debug.apk.

## Use
1. Plug the tablet into the phone (USB-C / OTG cable), open the app, tap Connect, allow USB access.
2. Move the pen. The cyan circle follows it; it draws when pressed. Line width follows pressure.
3. If the cursor moves wrongly: tap Learn, sweep the pen over the whole tablet pressing lightly and hard, then tap Done.
4. Still wrong? Tap Copy log and send me the text (it includes the tablet's HID descriptor) and I'll tune the parser.
5. Rotate turns the picture in 90 degree steps to match how the tablet is held.
