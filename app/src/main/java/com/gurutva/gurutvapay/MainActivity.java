package com.gurutva.gurutvapay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gurutva.gurutvapay_sdk.GurutvaPayActivity; // SDK Activity - adjust if package differs

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TransactionsAdapter.Callbacks {
    private static final int REQ_PAYMENT = 1001;

    private EditText etOrderId, etAmount, etName, etEmail, etPhone, etAddress1, etAddress2;
    private Button btnCreateOpen;
    private RecyclerView rvTxns;

    private final ArrayList<TransactionItem> txns = new ArrayList<>();
    private TransactionsAdapter adapter;

    // API key, change as needed
    private String liveSaltKey1 = "live_234f901f-666f-46dc-a6ca-b656d42636e7";
    private final String envBaseUrl = "https://api.gurutvapay.com/live";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etOrderId = findViewById(R.id.etOrderId);
        etAmount = findViewById(R.id.etAmount);
        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etPhone = findViewById(R.id.etPhone);
        etAddress1 = findViewById(R.id.etAddress1);
        etAddress2 = findViewById(R.id.etAddress2);
        btnCreateOpen = findViewById(R.id.btnCreateOpen);
        rvTxns = findViewById(R.id.rvTxns);

        adapter = new TransactionsAdapter(txns, this);
        rvTxns.setLayoutManager(new LinearLayoutManager(this));
        rvTxns.setAdapter(adapter);

        btnCreateOpen.setOnClickListener(v -> createOrderAndOpenSdk());
    }

    private void createOrderAndOpenSdk() {
        String orderId = etOrderId.getText().toString().trim();
        int amount = 0;
        try { amount = Integer.parseInt(etAmount.getText().toString().trim()); } catch (Exception ignored) {}

        if (orderId.isEmpty()) {
            etOrderId.setError("required");
            return;
        }

        TransactionItem txn = new TransactionItem(orderId, amount);
        txns.add(0, txn);
        adapter.notifyItemInserted(0);
        rvTxns.scrollToPosition(0);

        // Build order payload JSON
        try {
            JSONObject payload = new JSONObject();
            payload.put("amount", amount);
            payload.put("merchantOrderId", orderId);
            payload.put("channel", "android");
            payload.put("purpose", "Integration Test Payment");

            JSONObject cust = new JSONObject();
            cust.put("buyer_name", etName.getText().toString());
            cust.put("email", etEmail.getText().toString());
            cust.put("phone", etPhone.getText().toString());
            cust.put("address1", etAddress1.getText().toString());
            cust.put("address2", etAddress2.getText().toString());
            payload.put("customer", cust);

            // Start SDK activity (GurutvaPayActivity) with extras
            Intent i = new Intent(this, GurutvaPayActivity.class);
            i.putExtra("EXTRA_LIVE_SALT_KEY1", liveSaltKey1);
            i.putExtra("EXTRA_ORDER_PAYLOAD_JSON", payload.toString());
            i.putExtra("EXTRA_ENV_BASE_URL", envBaseUrl);
            startActivityForResult(i, REQ_PAYMENT);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Called when Check status clicked in adapter
    @Override
    public void onCheckStatus(int position) {
        TransactionItem t = txns.get(position);
        // run network call
        executor.submit(() -> {
            final String result = checkTransactionStatusSync(t.merchantOrderId);
            runOnUiThread(() -> {
                // result is JSON string or error message
                try {
                    JSONObject j = new JSONObject(result);
                    if (j.has("status")) {
                        t.status = j.optString("status");
                        t.orderId = j.optString("orderId", t.orderId);
                        t.transactionId = j.optString("transactionId", t.transactionId);
                    } else if (j.has("error")) {
                        // keep error message as status
                        t.status = "error: " + j.optString("error");
                    } else {
                        t.status = "unknown";
                    }
                } catch (Exception e) {
                    t.status = "error";
                }
                adapter.notifyItemChanged(position);
            });
        });
    }

    @Override
    public void onDetails(int position) {
        // optional: show more details or navigate to detail screen
    }

    private String checkTransactionStatusSync(String merchantOrderId) {
        HttpURLConnection conn = null;
        try {
            String base = envBaseUrl;
            String q = "?merchantOrderId=" + URLEncoder.encode(merchantOrderId, "UTF-8");
            URL url = new URL(base + "/transaction-status-android" + q);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); // as Dart used POST with query param
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Live-Salt-Key1", liveSaltKey1);
            conn.setRequestProperty("appId", getPackageName());
            // no body required (server might accept empty body with query param)
            conn.setFixedLengthStreamingMode(0);
            conn.connect();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            String body = sb.toString().trim();
            if (code >= 200 && code < 300) {
                return body;
            } else {
                JSONObject err = new JSONObject();
                err.put("error", "HTTP " + code + " - " + body);
                return err.toString();
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                return err.toString();
            } catch (Exception ex) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PAYMENT) return;

        if (resultCode == Activity.RESULT_OK && data != null) {
            String txnId = data.getStringExtra("transactionId");
            String mo = data.getStringExtra("merchantOrderId");
            String orderId = data.getStringExtra("orderId");

            // update local txn by merchantOrderId (mo)
            for (int i = 0; i < txns.size(); i++) {
                TransactionItem t = txns.get(i);
                if (t.merchantOrderId.equals(mo)) {
                    t.status = "success";
                    if (txnId != null) t.transactionId = txnId;
                    if (orderId != null) t.orderId = orderId;
                    adapter.notifyItemChanged(i);
                    // optionally confirm with server
                    confirmServerStatus(mo, i);
                    return;
                }
            }
            // fallback: update first txn
            if (!txns.isEmpty()) {
                TransactionItem t = txns.get(0);
                t.status = "success";
                if (txnId != null) t.transactionId = txnId;
                adapter.notifyItemChanged(0);
            }
        } else {
            String err = (data != null) ? data.getStringExtra("error") : "cancelled";
            // optionally show toast
        }
    }

    private void confirmServerStatus(String merchantOrderId, int position) {
        executor.submit(() -> {
            String result = checkTransactionStatusSync(merchantOrderId);
            runOnUiThread(() -> {
                try {
                    JSONObject j = new JSONObject(result);
                    TransactionItem t = txns.get(position);
                    if (j.has("status")) {
                        t.status = j.optString("status");
                        t.orderId = j.optString("orderId", t.orderId);
                        t.transactionId = j.optString("transactionId", t.transactionId);
                    }
                    adapter.notifyItemChanged(position);
                } catch (Exception e) {
                    // ignore
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
