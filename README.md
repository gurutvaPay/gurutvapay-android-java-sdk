This README explains how to integrate, configure, and use the GurutvaPay Android SDK (Java) in the sample app. It covers both the native Android app integration and how to use the SDK from a Flutter project (module or AAR). Follow the sections below to set up quickly, run the sample, call the SDK, and troubleshoot common issues.

Contents

What is included

Requirements

Project layout

Install / include SDK module

Option A — include SDK module (dev)

Option B — use built .aar (binary)

Android Gradle / compileSdk note

Android manifest & permissions

Start SDK from native app (Java)

Integrate SDK with Flutter (MethodChannel)

SDK Activity behavior & intents

Check transaction status

Build AAR / publish locally

ProGuard / R8 rules

UI notes (gradients, header)

Troubleshooting

Next steps / improvements

What is included

gurutvapay (Android library module)

GurutvaPayActivity — WebView payment flow (loads server payment_url)

Optional GurutvaPay helper entrypoint (startPayment convenience)

layout drawables for brand gradient and header

app (sample app)

MainActivity.java — sample UI to create order, open SDK, list transactions, check status

TransactionsAdapter, TransactionItem — UI helpers

README (this file)

Requirements

Android Studio (recommended) or command line Gradle

Android SDK Platform: API 35 (ensure installed)

Gradle & Android Gradle Plugin compatible with API 35

Java 8+ (compileOptions currently set to Java 8)

For Flutter integration: Flutter + Android toolchain set up

Project layout
GurutvaPaySDKSample/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/...
  gurutvapay/
    build.gradle.kts
    src/main/java/com/gurutva/gurutvapay_sdk/...
    src/main/res/...

Install / include SDK module
Option A — Include SDK module in project (recommended for development)

Copy gurutvapay/ folder into your project root (next to app/).

settings.gradle.kts:

include(":app", ":gurutvapay")
project(":gurutvapay").projectDir = file("gurutvapay")


app/build.gradle.kts:

dependencies {
    implementation(project(":gurutvapay"))
    // other deps...
}


Sync project.

Option B — Use AAR (binary)

Build the AAR (see Build AAR
).

Copy the .aar to app/libs/.

app/build.gradle (Groovy) add:

repositories { flatDir { dirs 'libs' } }

dependencies {
    implementation(name: 'gurutvapay-release', ext: 'aar')
}


Sync project.

Android Gradle / compileSdk note

The SDK and app should use the same compileSdk. RecyclerView and modern AndroidX libs may require compileSdk >= 35.

In build.gradle.kts (app & gurutvapay):

android {
    compileSdk = 35
    defaultConfig {
        targetSdk = 35
        minSdk = 24 // or as required
    }
}


Install Android 35 via SDK Manager if needed.

Android manifest & permissions

Ensure INTERNET permission is present (either in app manifest or library manifest):

<uses-permission android:name="android.permission.INTERNET" />


Register the SDK activity if not declared in the library manifest (normally library manifest merges automatically):

<activity
    android:name="com.gurutva.gurutvapay_sdk.GurutvaPayActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.Light.NoActionBar" />

Start SDK from native app (Java)

Use the SDK Activity directly or GurutvaPay helper.

Example — start SDK GurutvaPayActivity with order payload:

JSONObject payload = new JSONObject();
// build payload fields (amount, merchantOrderId, channel, customer, ...)
Intent i = new Intent(this, GurutvaPayActivity.class);
i.putExtra(GurutvaPayActivity.EXTRA_LIVE_SALT_KEY1, "live_XXX");
i.putExtra(GurutvaPayActivity.EXTRA_ORDER_PAYLOAD_JSON, payload.toString());
i.putExtra(GurutvaPayActivity.EXTRA_ENV_BASE_URL, "https://api.gurutvapay.com/live");
startActivityForResult(i, REQ_PAYMENT);


Handle result (success / failure):

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQ_PAYMENT) {
        if (resultCode == Activity.RESULT_OK) {
            String txn = data.getStringExtra("transactionId");
            String mo  = data.getStringExtra("merchantOrderId");
            // success handling
        } else {
            String err = data != null ? data.getStringExtra("error") : "cancelled";
        }
    }
}

Integrate SDK with Flutter (MethodChannel)

You can start the native SDK from Flutter via a MethodChannel bridge in MainActivity.java.

Add gurutvapay module to Flutter android/ (Option A) or include AAR (Option B).

Implement MethodChannel in android/app/src/main/java/.../MainActivity.java:

new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), "gurutvapay_channel")
    .setMethodCallHandler((call, result) -> {
        if ("startPayment".equals(call.method)) {
            // read args: orderPayload, merchantOrderId, amount, liveSaltKey1
            // keep pending Result, then startActivityForResult GurutvaPayActivity
        }
    });


In onActivityResult, convert activity result to a Map and call pendingResult.success(map).

In Dart, call:

final Map res = await MethodChannel('gurutvapay_channel').invokeMethod('startPayment', {
  'orderPayload': payload,
  'liveSaltKey1': 'live_XXX'
});


Note: earlier in the project you already have a working example of this bridge. Ensure package names match.

SDK Activity behavior & intents

GurutvaPayActivity:

Posts orderPayload JSON to envBaseUrl/initiate-payment-android with headers:

Live-Salt-Key1: provided API key

appId: package name

Expects payment_url in response JSON.

Loads payment_url in a WebView.

Injects JS to capture console.log, console.error, and postMessage.

When it detects a JSON payload with status: "success" it may call setResult(RESULT_OK, intent) with transactionId, merchantOrderId, and orderId.

On error it uses RESULT_CANCELED with error.

Handles UPI / intent: links by attempting to launch external UPI apps (PhonePe, Paytm, GPay) and deduplicates rapid launches.

Important: The Activity may auto-finish on success. If you prefer host-controlled navigation, modify finishWithSuccess behavior.

Check transaction status

SDK helper (and the Flutter widget) uses:

POST https://api.gurutvapay.com/live/transaction-status-android?merchantOrderId=... 
Headers:
  Live-Salt-Key1: <key>
  appId: <your.app.id>


Response expected to be JSON with status, orderId, transactionId, etc.

Sample Java sync helper (used in sample MainActivity):

URL url = new URL(envBaseUrl + "/transaction-status-android?merchantOrderId=" + URLEncoder.encode(mo,"UTF-8"));
HttpURLConnection conn = (HttpURLConnection) url.openConnection();
conn.setRequestMethod("POST");
conn.setRequestProperty("Live-Salt-Key1", liveSaltKey1);
conn.setRequestProperty("appId", getPackageName());
// read response

Build AAR / publish locally

Build AAR:

./gradlew :gurutvapay:assembleRelease
# AAR at gurutvapay/build/outputs/aar/gurutvapay-release.aar


Publish to local Maven (optional):

Add maven-publish config in gurutvapay/build.gradle.kts (MavenPublication).

Run:

./gradlew :gurutvapay:publishToMavenLocal


Then consume via implementation("com.yourcompany:gurutvapay:1.0.0") and ensure mavenLocal() is in repositories.

ProGuard / R8 rules

If your SDK uses reflection or JSON libs, include consumerProguardFiles in the library module:

android {
  defaultConfig {
    consumerProguardFiles("consumer-rules.pro")
  }
}


Add rules to consumer-rules.pro and they will be merged into host app builds.

UI notes (gradients, header)

Two drawables provided:

res/drawable/brand_gradient_full.xml — background gradient (orange → violet).

res/drawable/brand_gradient_header.xml — header gradient (rounded bottom corners).

Title gradient (text) can be applied programmatically:

TextView title = findViewById(R.id.title);
title.post(() -> {
  Shader shader = new LinearGradient(0,0, title.getMeasuredWidth(), title.getTextSize(),
      new int[]{0xFFFFA500, 0xFF8F00FF}, null, Shader.TileMode.CLAMP);
  title.getPaint().setShader(shader);
});

Troubleshooting

AAR metadata error requiring compileSdk 35 — increase compileSdk in all modules to 35 and install Android 35 platform.

Unresolved plugin alias — if using libs.versions.toml ensure android-library alias exists; or use id("com.android.library") directly.

Manifest merge missing activity — ensure GurutvaPayActivity declared in library manifest or app manifest.

MethodChannel pendingResult lost — avoid process death; handle only one concurrent payment or map request IDs to results.

Next steps / improvements

Convert integration into a formal Flutter plugin (platform interface + Android/Java implementation).

Replace HttpURLConnection with OkHttp for robust networking and interceptors.

Use modern Activity Result API instead of startActivityForResult for robustness.

Add instrumentation/UI tests for payment flows and JS bridge cases.

Harden error handling, analytics, and logging. Provide retry options and deep-link callbacks.

Contact / Support

If you need help adapting the SDK to your app architecture, converting to a Flutter plugin, or publishing to Maven Central / JitPack, paste your settings.gradle(.kts), app/build.gradle(.kts) and I’ll prepare exact patches or a plugin skeleton.

License

(Include your license here — e.g., MIT / Proprietary / Company internal policy.)
