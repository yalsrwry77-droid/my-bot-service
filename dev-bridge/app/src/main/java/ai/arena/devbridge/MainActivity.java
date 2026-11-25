package ai.arena.devbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Pairing / enable UI for the session dev bridge (Arabic, programmatic layout
 * like the original bridge — no XML layouts, no external dependencies).
 *
 * <p>Flow:
 * <ol>
 *   <li>App generates a device UUID + 32-byte token on first launch.</li>
 *   <li>User enters the 32-hex pairing code (from Supabase
 *       {@code bridge_pair_codes}) and taps "اقرن الهاتف".</li>
 *   <li>App POSTs {@code {op:"pair"}}; on success stores {@code paired=true}.</li>
 *   <li>User taps "تشغيل الجسر" → root check → starts {@link BridgeService}.</li>
 * </ol>
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView identView;
    private TextView stateView;
    private EditText codeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        ensureIdentity();
        buildUi();
        refreshState();
    }

    private void ensureIdentity() {
        String id = prefs.getString(Config.KEY_IDENT, null);
        if (id == null || id.isEmpty()) {
            prefs.edit().putString(Config.KEY_IDENT, UUID.randomUUID().toString()).apply();
        }
        String token = prefs.getString(Config.KEY_TOKEN, null);
        if (token == null || token.isEmpty()) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            prefs.edit().putString(Config.KEY_TOKEN, sb.toString()).apply();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("جسر تطوير الهاتف — جلسة التطوير");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView warn = new TextView(this);
        warn.setText("عند تشغيل الجسر يُسمح بتنفيذ أوامر بصلاحية الروت عبر الإنترنت، "
                + "بما في ذلك تعديل الهاتف وتثبيت التطبيقات. استخدمه فقط على جهازك الخاص.");
        warn.setTextSize(13);
        warn.setPadding(0, dp(8), 0, dp(8));
        root.addView(warn);

        identView = new TextView(this);
        identView.setTextIsSelectable(true);
        identView.setTextSize(12);
        root.addView(identView);

        stateView = new TextView(this);
        stateView.setTextSize(14);
        stateView.setPadding(0, dp(8), 0, dp(8));
        root.addView(stateView);

        codeView = new EditText(this);
        codeView.setHint("رمز الاقتران — أدخل رمز الاقتران (32 حرفًا)");
        codeView.setSingleLine(true);
        codeView.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(codeView);

        Button pairBtn = new Button(this);
        pairBtn.setText("اقرن الهاتف");
        pairBtn.setOnClickListener(v -> pair());
        root.addView(pairBtn);

        Button enableBtn = new Button(this);
        enableBtn.setText("تشغيل الجسر وإعدادات توفير الطاقة");
        enableBtn.setOnClickListener(v -> enable());
        root.addView(enableBtn);

        Button batteryBtn = new Button(this);
        batteryBtn.setText("إعدادات توفير الطاقة");
        batteryBtn.setOnClickListener(v -> openBatterySettings());
        root.addView(batteryBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("إيقاف الجسر وسحب الصلاحية");
        stopBtn.setOnClickListener(v -> disable());
        root.addView(stopBtn);
    }

    private void pair() {
        String code = codeView.getText().toString().trim().toLowerCase();
        if (!code.matches("[0-9a-f]{32}")) {
            setState("فشل الاقتران: رمز غير صالح (32 حرفًا hex)");
            return;
        }
        setState("جارٍ الاقتران…");
        new Thread(() -> {
            JSONObject body = new JSONObject();
            try {
                body.put("id", prefs.getString(Config.KEY_IDENT, ""));
                body.put("token", prefs.getString(Config.KEY_TOKEN, ""));
                body.put("op", "pair");
                body.put("code", code);
            } catch (Exception ignored) {
            }
            JSONObject resp = post(body);
            boolean ok = resp != null && resp.optBoolean("ok", false);
            runOnUiThread(() -> {
                if (ok) {
                    prefs.edit().putBoolean(Config.KEY_PAIRED, true).apply();
                    setState("تم الاقتران. الهاتف مقترن؛ يمكنك تشغيل الجسر الآن.");
                } else {
                    setState("فشل الاقتران: رمز غير صالح أو منتهي");
                }
                refreshState();
            });
        }).start();
    }

    private void enable() {
        if (!prefs.getBoolean(Config.KEY_PAIRED, false)) {
            setState("الجهاز غير مقترن. اقرن الهاتف أولًا.");
            return;
        }
        setState("جارٍ فحص صلاحية الروت؛ وافق على طلب Magisk إن ظهر…");
        new Thread(() -> {
            String rootOut;
            try {
                rootOut = rootCommand("id -u");
            } catch (Exception e) {
                rootOut = "error: " + e.getMessage();
            }
            final boolean rootOk = rootOut != null && rootOut.contains("exit=0");
            runOnUiThread(() -> {
                if (!rootOk) {
                    setState("لم تتوفر صلاحية الروت. تحقق من Magisk وحاول مجددًا.");
                    return;
                }
                prefs.edit().putBoolean(Config.KEY_ENABLED, true).apply();
                Intent svc = new Intent(this, BridgeService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                setState("تم طلب تشغيل الجسر. تحقق من الإشعار. "
                        + "التشغيل مفعّل. تحقق من الإشعار.");
                openBatterySettings();
                refreshState();
            });
        }).start();
    }

    private void disable() {
        prefs.edit().putBoolean(Config.KEY_ENABLED, false).apply();
        Intent stop = new Intent(this, BridgeService.class);
        stop.setAction(Config.STOP_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(stop);
        } else {
            startService(stop);
        }
        setState("تم إيقاف الجسر. لإلغاء صلاحية Magisk أيضًا أزل التطبيق من قائمة Magisk.");
        refreshState();
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshState() {
        String id = prefs.getString(Config.KEY_IDENT, "");
        boolean paired = prefs.getBoolean(Config.KEY_PAIRED, false);
        boolean enabled = prefs.getBoolean(Config.KEY_ENABLED, false);
        identView.setText("معرّف الجهاز: " + id);
        if (enabled) {
            setState("جسر التطوير يعمل — وصول جذري عن بُعد مُفعّل");
        } else if (paired) {
            setState("الهاتف مقترن؛ الجسر متوقف. اضغط تشغيل الجسر للبدء.");
        } else {
            setState("الجهاز غير مقترن. أدخل رمز الاقتران ثم اضغط اقرن الهاتف.");
        }
    }

    private void setState(String msg) {
        if (stateView != null) stateView.setText(msg);
    }

    private JSONObject post(JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(Config.BRIDGE_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(Config.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Config.READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            String text = baos.toString("UTF-8");
            if (text == null || text.trim().isEmpty()) return null;
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String rootCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
        pb.redirectErrorStream(true);
        Process child = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream is = child.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            } catch (Exception ignored) {
            }
        });
        reader.start();
        boolean finished = child.waitFor(Config.COMMAND_TIMEOUT_SEC,
                java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            child.destroy();
            reader.join(2000);
            return "timeout after " + Config.COMMAND_TIMEOUT_SEC + "s\n" + out.toString("UTF-8");
        }
        reader.join(2000);
        return "exit=" + child.exitValue() + "\n" + out.toString("UTF-8");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
