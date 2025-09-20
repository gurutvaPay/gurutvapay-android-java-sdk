package com.gurutva.gurutvapay_sdk;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GurutvaPayActivity
 *
 * Intent extras:
 *  - EXTRA_LIVE_SALT_KEY1 (String)  [required for server auth]
 *  - EXTRA_ORDER_PAYLOAD_JSON (String)  (JSON string of the orderPayload)
 *  - EXTRA_ENV_BASE_URL (String) optional, defaults to https://api.gurutvapay.com/live
 *
 * Result (on success):
 *  setResult(Activity.RESULT_OK, intent) where intent contains:
 *    - "transactionId"
 *    - "merchantOrderId"
 *    - optional "orderId"
 *
 * On failure: setResult(Activity.RESULT_CANCELED, intent) with "error"
 */
public class GurutvaPayActivity extends AppCompatActivity {
    private static final String TAG = "GurutvaPayActivity";

    public static final String EXTRA_LIVE_SALT_KEY1 = "EXTRA_LIVE_SALT_KEY1";
    public static final String EXTRA_ORDER_PAYLOAD_JSON = "EXTRA_ORDER_PAYLOAD_JSON";
    public static final String EXTRA_ENV_BASE_URL = "EXTRA_ENV_BASE_URL";

    private static final String DEFAULT_BASE = "https://api.gurutvapay.com/live";
    private static final long INTENT_DEDUPE_WINDOW_MS = 8_000L;

    private WebView webView;
    private View overlayLoading;
    private TextView tvInfo;
    private Button btnRetry;
    private ImageView logo;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // dedupe for external intent launches
    private final Map<String, Long> intentLaunchedAt = Collections.synchronizedMap(new HashMap<>());
    // currently launching (simple lock)
    private final Map<String, Boolean> currentlyLaunching = Collections.synchronizedMap(new HashMap<>());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gurutva_pay);

        webView = findViewById(R.id.webview);
        overlayLoading = findViewById(R.id.overlayLoading);
        tvInfo = findViewById(R.id.tvInfo);
        btnRetry = findViewById(R.id.btnRetry);
        logo = findViewById(R.id.logoImg);

        btnRetry.setOnClickListener(v -> initiatePayment());

        setupWebView();
        startLoaderAnimation();

        initiatePayment();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadsImagesAutomatically(true);

        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                final String msg = consoleMessage.message();
                handleConsoleMessage(msg);
                return super.onConsoleMessage(consoleMessage);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isIntentOrUpi(url)) {
                    handleUpiOrIntent(url, null);
                    return true; // don't let the webview load it
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectConsoleOverrideJS();
                overlayLoading.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                tvInfo.setText("Load error: " + error.getDescription());
                overlayLoading.setVisibility(View.GONE);
            }
        });
    }

    private void startLoaderAnimation() {
        RotateAnimation anim = new RotateAnimation(0f, 360f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(1400);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(RotateAnimation.INFINITE);
        logo.startAnimation(anim);
    }

    private void initiatePayment() {
        overlayLoading.setVisibility(View.VISIBLE);
        tvInfo.setText("Creating payment session...");
        btnRetry.setVisibility(View.GONE);

        final String liveSalt = getIntent().getStringExtra(EXTRA_LIVE_SALT_KEY1);
        final String orderJson = getIntent().getStringExtra(EXTRA_ORDER_PAYLOAD_JSON);
        final String base = getIntent().getStringExtra(EXTRA_ENV_BASE_URL);
        final String envBase = base != null ? base : DEFAULT_BASE;

        executor.submit(() -> {
            HttpURLConnection conn = null;
            try {
                String apiUrl = envBase.endsWith("/") ? envBase + "initiate-payment-android" : envBase + "/initiate-payment-android";
                URL url = new URL(apiUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                if (liveSalt != null) conn.setRequestProperty("Live-Salt-Key1", liveSalt);
                conn.setRequestProperty("appId", getPackageName());

                // write body (order payload json)
                if (orderJson != null) {
                    byte[] payloadBytes = orderJson.getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(payloadBytes.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payloadBytes);
                    }
                } else {
                    // empty body allowed; keep server contract in mind
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                final String body = sb.toString().trim();
                if (code >= 200 && code < 300) {
                    // parse response JSON and extract payment_url
                    try {
                        JSONObject j = new JSONObject(body);
                        if (j.has("payment_url")) {
                            final String purl = j.optString("payment_url");
                            mainHandler.post(() -> loadPaymentUrl(purl));
                        } else {
                            mainHandler.post(() -> showError("missing payment_url in response: " + body));
                        }
                    } catch (JSONException e) {
                        mainHandler.post(() -> showError("invalid json response: " + body));
                    }
                } else {
                    mainHandler.post(() -> showError("HTTP " + code + " - " + body));
                }
            } catch (Exception e) {
                Log.e(TAG, "initiate error", e);
                mainHandler.post(() -> showError("Network error: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void loadPaymentUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            showError("Empty payment URL");
            return;
        }
        overlayLoading.setVisibility(View.VISIBLE);
        tvInfo.setText("Loading payment...");
        webView.loadUrl(url);
    }

    private void showError(String msg) {
        overlayLoading.setVisibility(View.GONE);
        tvInfo.setText("Error: " + msg);
        btnRetry.setVisibility(View.VISIBLE);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /**
     * Inject JS into the page to forward console.log/error and postMessage via AndroidBridge.onMessage(...)
     */
    private void injectConsoleOverrideJS() {
        final String js = "(function(){"
                + "if (window.__gurutva_console_installed) return;"
                + "window.__gurutva_console_installed = true;"
                + "function send(kind, args){"
                + "  try {"
                + "    var parts = Array.prototype.slice.call(args).map(function(a){"
                + "      try { return (typeof a === 'object' ? JSON.stringify(a) : String(a));} catch(e){ return String(a);}"
                + "    }).join(' ');"
                + "    AndroidBridge.onMessage(JSON.stringify({kind:kind, payload: parts}));"
                + "  } catch(e) {}"
                + "}"
                + "var oldLog = console.log; console.log = function(){ send('log', arguments); oldLog && oldLog.apply(console, arguments); };"
                + "var oldErr = console.error; console.error = function(){ send('error', arguments); oldErr && oldErr.apply(console, arguments); };"
                + "window.addEventListener('message', function(e){ try { var d = e.data; var payload = (typeof d === 'object') ? JSON.stringify(d) : String(d); AndroidBridge.onMessage(JSON.stringify({kind:'postMessage', payload: payload})); } catch(e){} }, false);"
                + "})();";
        // evaluate javascript in the page
        webView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String s) { /* ignore */ }
        });
    }

    private boolean isIntentOrUpi(String url) {
        if (url == null) return false;
        final String low = url.toLowerCase();
        return low.startsWith("intent:") || low.startsWith("upi:") || low.startsWith("upi://") ||
                low.startsWith("phonepe://") || low.startsWith("paytmmp://") || low.startsWith("tez://");
    }

    private void handleConsoleMessage(String msg) {
        if (msg == null) return;
        handleConsoleString(msg);
    }

    /**
     * Handle console JSON or plain string
     */
    private void handleConsoleString(String text) {
        final String t = text.trim();
        if (t.isEmpty()) return;

        // if looks like JSON try to parse
        try {
            JSONObject parsed = new JSONObject(t);
            handleConsoleObject(parsed);
            return;
        } catch (JSONException ignored) {}

        // try extracting an intent/upi token
        if (t.toLowerCase().contains("upi:") || t.toLowerCase().contains("intent:") || t.toLowerCase().contains("upi://")) {
            // find first matching substring
            String found = findFirstMatch(t, "(intent:[^\\s\"<>]+|upi:[^\\s\"<>]+|upi://[^\\s\"<>]+)");
            if (found != null) {
                handleUpiOrIntent(found, null);
                return;
            }
        }

        // try extract "status" from string like: "{"status":"success",...}"
        String status = extractJsonFieldFromString(t, "status");
        if (status != null) {
            Map<String, String> event = new HashMap<>();
            event.put("status", status);
            // treat success/failure/pending
            if (status.toLowerCase().contains("success")) {
                // we didn't get transactionId here — rely on parsed JSON flow or page redirect
                finishWithSuccess(null, null);
            } else if (status.toLowerCase().contains("pending")) {
                Toast.makeText(this, "Payment pending", Toast.LENGTH_SHORT).show();
            } else {
                finishWithFailure("status: " + status);
            }
            return;
        }

        // fallback: log
        Log.d(TAG, "Console: " + t);
    }

    private String extractJsonFieldFromString(String s, String key) {
        try {
            JSONObject j = new JSONObject(s);
            if (j.has(key)) return j.optString(key);
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    private String findFirstMatch(String text, String regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return m.group(0);
        return null;
    }

    private void handleConsoleObject(JSONObject obj) {
        // If the object contains "merchantOrderId" or "transactionId" or "status", act on it.
        try {
            String status = obj.optString("status", null);
            String mo = obj.optString("merchantOrderId", null);
            String txn = obj.optString("transactionId", null);
            String orderId = obj.optString("orderId", null);

            if (status != null && status.toLowerCase().contains("success")) {
                finishWithSuccess(txn, mo != null ? mo : null, orderId);
                return;
            } else if (status != null && (status.toLowerCase().contains("fail") || status.toLowerCase().contains("error"))) {
                String err = obj.optString("error", "payment failed");
                finishWithFailure(err);
                return;
            } else if (status != null && status.toLowerCase().contains("pending")) {
                Toast.makeText(this, "Payment pending", Toast.LENGTH_SHORT).show();
                return;
            }

            // Handle special upi payload shapes: { kind: "upi_intent", payload: { url: "...", app: "..."} }
            String kind = obj.optString("kind", null);
            if ("upi_intent".equalsIgnoreCase(kind)) {
                JSONObject payload = obj.optJSONObject("payload");
                if (payload != null && payload.has("url")) {
                    String url = payload.optString("url");
                    String app = payload.optString("app", null);
                    handleUpiOrIntent(url, app);
                    return;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "handleConsoleObject error", e);
        }
    }

    private void handleUpiOrIntent(String url, @Nullable String appHint) {
        if (url == null || url.trim().isEmpty()) return;

        final String key = (appHint != null ? appHint : "") + "::" + url;
        final long now = System.currentTimeMillis();
        Long last = intentLaunchedAt.get(key);
        if (last != null && (now - last) <= INTENT_DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Duplicate intent ignored: " + key);
            return;
        }
        if (currentlyLaunching.containsKey(key)) {
            Log.d(TAG, "Already launching: " + key);
            return;
        }
        currentlyLaunching.put(key, true);

        // Try parsing "intent:" specially
        if (url.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    intentLaunchedAt.put(key, System.currentTimeMillis());
                } catch (ActivityNotFoundException ex) {
                    // fallback: try to open market URL if present in intent
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        openExternalUrl(fallback);
                        intentLaunchedAt.put(key, System.currentTimeMillis());
                    } else {
                        Toast.makeText(this, "No app available to handle intent", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "intent parse failed", e);
            } finally {
                scheduleClearCurrentlyLaunching(key);
            }
            return;
        }

        // Build candidate variants if it's a UPI URL
        final String low = url.toLowerCase();
        if (low.startsWith("upi:") || low.startsWith("upi://")) {
            // attempt common app schemes: phonepe, paytm, gpay (tez)
            String upi = url;
            String phonepe = upi.replaceFirst("upi://pay", "phonepe://pay");
            String paytm = upi.replaceFirst("upi://pay", "paytmmp://pay");
            String gpay = upi.replaceFirst("upi://pay", "tez://upi/pay");

            // try variants in order (appHint if present)
            if (appHint != null) {
                String hint = appHint.toLowerCase();
                if (hint.contains("phonepe")) { tryLaunchSingle(phonepe, key); return; }
                if (hint.contains("paytm"))  { tryLaunchSingle(paytm, key); return; }
                if (hint.contains("gpay") || hint.contains("google")) { tryLaunchSingle(gpay, key); return; }
            }

            // try UPI variants sequentially
            if (tryLaunchSingle(upi, key)) { return; }
            if (tryLaunchSingle(phonepe, key)) { return; }
            if (tryLaunchSingle(paytm, key)) { return; }
            if (tryLaunchSingle(gpay, key)) { return; }

            // fallback open the upi link as VIEW
            openExternalUrl(upi);
            intentLaunchedAt.put(key, System.currentTimeMillis());
            scheduleClearCurrentlyLaunching(key);
            return;
        }

        // If scheme is app-specific like phonepe:// or paytmmp://
        if (low.startsWith("phonepe://") || low.startsWith("paytmmp://") || low.startsWith("tez://")) {
            if (tryLaunchSingle(url, key)) return;
            openExternalUrl(url);
            intentLaunchedAt.put(key, System.currentTimeMillis());
            scheduleClearCurrentlyLaunching(key);
            return;
        }

        // default fallback: try openExternally
        openExternalUrl(url);
        intentLaunchedAt.put(key, System.currentTimeMillis());
        scheduleClearCurrentlyLaunching(key);
    }

    private boolean tryLaunchSingle(String schemeUrl, String key) {
        if (schemeUrl == null || schemeUrl.isEmpty()) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(schemeUrl));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(i);
                intentLaunchedAt.put(key, System.currentTimeMillis());
                scheduleClearCurrentlyLaunching(key);
                return true;
            } catch (ActivityNotFoundException e) {
                // continue to next variant
            }
        }
        return false;
    }

    private void openExternalUrl(String url) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open: " + url, Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleClearCurrentlyLaunching(String key) {
        mainHandler.postDelayed(() -> currentlyLaunching.remove(key), 6_000L);
    }

    private void finishWithSuccess(@Nullable String txnId, @Nullable String merchantOrderId) {
        finishWithSuccess(txnId, merchantOrderId, null);
    }

    private void finishWithSuccess(@Nullable String txnId, @Nullable String merchantOrderId, @Nullable String orderId) {
        Intent res = new Intent();
        if (txnId != null) res.putExtra("transactionId", txnId);
        if (merchantOrderId != null) res.putExtra("merchantOrderId", merchantOrderId);
        if (orderId != null) res.putExtra("orderId", orderId);
        setResult(Activity.RESULT_OK, res);
        finish();
    }

    private void finishWithFailure(String error) {
        Intent res = new Intent();
        res.putExtra("error", error);
        setResult(Activity.RESULT_CANCELED, res);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * Bridge class exposed to JS as AndroidBridge.onMessage(jsonString)
     */
    private class JsBridge {
        @JavascriptInterface
        public void onMessage(String json) {
            if (json == null) return;
            // json is something like {"kind":"log","payload":"..."} or {"kind":"postMessage","payload":"..."}
            try {
                JSONObject wrapper = new JSONObject(json);
                String kind = wrapper.optString("kind", "log");
                String payload = wrapper.optString("payload", null);
                if (payload == null) return;

                // try parse payload as JSON
                try {
                    JSONObject parsedPayload = new JSONObject(payload);
                    handleConsoleObject(parsedPayload);
                } catch (JSONException e) {
                    // not JSON, treat as string
                    handleConsoleString(payload);
                }
            } catch (JSONException e) {
                Log.w(TAG, "invalid bridge message", e);
            }
        }
    }
}
