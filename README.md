# GurutvaPay Android SDK

This README explains how to integrate, configure, and use the **GurutvaPay** Android SDK (Java) in the sample **app**.  
It covers both the **native Android app** integration and how to use the SDK from a **Flutter project**.

---

## 📦 What is included
- **SDK module**: `gurutvapay/`
  - `GurutvaPayActivity` — WebView payment flow
  - Optional `GurutvaPay` helper entrypoint
  - Gradient UI drawables
- **Sample app**: `app/`
  - `MainActivity.java` — create order, open SDK, list transactions, check status
  - `TransactionItem` + `TransactionsAdapter`

---

## ⚙️ Requirements
- Android Studio (or command-line Gradle)
- Android SDK Platform **API 35**
- Gradle + Android Gradle Plugin for API 35
- Java 8+
- (Optional) Flutter toolchain for MethodChannel integration

---

## 📂 Project layout
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



---

## 🔌 Installation

### Option A — Include SDK module (development)
1. Copy `gurutvapay/` into your project root.
2. In `settings.gradle.kts`:
   ```kotlin
   include(":app", ":gurutvapay")
   project(":gurutvapay").projectDir = file("gurutvapay")


In app/build.gradle.kts:
```
dependencies {
    implementation(project(":gurutvapay"))
}
```


Both modules must use compileSdk = 35:

```
android {
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        targetSdk = 35
    }
}
```

Add internet permission:

```
<uses-permission android:name="android.permission.INTERNET" />
```

Register SDK activity (usually merged automatically):

```
<activity
    android:name="com.gurutva.gurutvapay_sdk.GurutvaPayActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.Light.NoActionBar" />
```
Using SDK from Native Java App
Start SDK Activity:
```
JSONObject payload = new JSONObject();
payload.put("amount", 100);
payload.put("merchantOrderId", "ORDER_123");
payload.put("channel", "android");
// add customer details...

Intent i = new Intent(this, GurutvaPayActivity.class);
i.putExtra(GurutvaPayActivity.EXTRA_LIVE_SALT_KEY1, "live_XXXX");
i.putExtra(GurutvaPayActivity.EXTRA_ORDER_PAYLOAD_JSON, payload.toString());
i.putExtra(GurutvaPayActivity.EXTRA_ENV_BASE_URL, "https://api.gurutvapay.com/live");
startActivityForResult(i, REQ_PAYMENT);
```

Handle result:
```
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQ_PAYMENT) {
        if (resultCode == Activity.RESULT_OK) {
            String txn = data.getStringExtra("transactionId");
            String mo  = data.getStringExtra("merchantOrderId");
            // success
        } else {
            String err = data != null ? data.getStringExtra("error") : "cancelled";
            // failure
        }
    }
}
```

Using SDK from Flutter (MethodChannel)

In MainActivity.java:

```
new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), "gurutvapay_channel")
    .setMethodCallHandler((call, result) -> {
        if ("startPayment".equals(call.method)) {
            // read args and start GurutvaPayActivity
        }
    });
```

Troubleshooting

compileSdk error → ensure both modules use compileSdk = 35.

Unresolved plugin alias → use id("com.android.library") instead of alias if needed.

Missing activity → check manifest merge.

Flutter channel lost → handle only one pending payment call.

🚀 Next Steps

Wrap into official Flutter plugin (platform interface + Android impl).

Use OkHttp for networking.

Replace startActivityForResult with Activity Result API.

Add automated UI tests.

📧 Contact

For integration help:
Team GurutvaPay
📩 info@gurutvapay.com
