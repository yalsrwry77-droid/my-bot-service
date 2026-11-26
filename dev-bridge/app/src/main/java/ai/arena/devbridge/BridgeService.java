package ai.arena.devbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that polls the session bridge backend and executes
 * commands with root shell access.
 *
 * <p>Protocol (same as original bridge, separate slug):
 * <pre>
 *   POST {BRIDGE_URL}  Content-Type: application/json
 *   { "id": "&lt;uuid&gt;", "token": "&lt;64 hex&gt;", "op": "poll" }
 *   -> { "ok": true, "command": { "id": "...", "action": "...", "args": {...} } | null }
 *
 *   POST { "id": ..., "token": ..., "op": "result",
 *          "command_id": "...", "message": "...", "success": true|false }
 * </pre>
 *
 * <p>Actions: {@code ping}, {@code root_check}, {@code root_shell}, {@code disconnect}.
 */
public class BridgeService extends Service {

    private SharedPreferences prefs;
    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wake;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        createChannel();
        startForeground(Config.NOTIFICATION_ID, buildNotification("جسر التطوير يعمل"));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "devbridge:wake");
            wake.setReferenceCounted(false);
            if (!wake.isHeld()) wake.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Config.STOP_ACTION.equals(intent.getAction())) {
            disable();
            return Service.START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "devbridge-worker");
            worker.start();
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        while (running) {
            try {
                String id = prefs.getString(Config.KEY_IDENT, "");
                String token = prefs.getString(Config.KEY_TOKEN, "");
                if (id == null || id.isEmpty() || token == null || token.isEmpty()) {
                    sleep(Config.POLL_INTERVAL_MS);
                    continue;
                }
                JSONObject poll = new JSONObject();
                poll.put("id", id);
                poll.put("token", token);
                poll.put("op", "poll");
                JSONObject resp = post(poll);
                if (resp != null && resp.optBoolean("ok", false)) {
                    JSONObject cmd = resp.optJSONObject("command");
                    if (cmd != null) {
                        execute(cmd);
                    }
                }
            } catch (Exception e) {
                // Keep polling; transient network errors must not kill the service.
            }
            sleep(Config.POLL_INTERVAL_MS);
        }
    }

    private void execute(JSONObject cmd) {
        String commandId = cmd.optString("id", "");
        String action = cmd.optString("action", "");
        JSONObject args = cmd.optJSONObject("args");
        String message;
        boolean success = true;
        try {
            switch (action) {
                case "ping":
                    message = "pong; bridge=" + Config.BRIDGE_VERSION
                            + "; android=" + Build.VERSION.RELEASE;
                    break;
                case "root_check":
                    message = rootCommand("id -u");
                    break;
                case "root_shell": {
                    String shellCmd = args != null ? args.optString("command", "") : "";
                    if (shellCmd == null || shellCmd.trim().isEmpty()) {
                        message = "رفض: أمر فارغ";
                        success = false;
                    } else {
                        message = rootCommand(shellCmd);
                    }
                    break;
                }
                case "screenshot": {
                    int width = args != null ? args.optInt("width", Config.SHOT_WIDTH) : Config.SHOT_WIDTH;
                    message = takeScreenshot(width);
                    break;
                }
                case "ui_tree": {
                    message = uiTree();
                    break;
                }
                case "disconnect":
                    message = "تم إيقاف الجسر";
                    postResult(commandId, message, true);
                    disable();
                    return;
                default:
                    message = "unsupported action: " + action;
                    success = false;
                    break;
            }
        } catch (Exception e) {
            message = "command failed: " + e.getMessage();
            success = false;
        }
        postResult(commandId, message, success);
    }

    private void postResult(String commandId, String message, boolean success) {
        try {
            String id = prefs.getString(Config.KEY_IDENT, "");
            String token = prefs.getString(Config.KEY_TOKEN, "");
            JSONObject body = new JSONObject();
            body.put("id", id);
            body.put("token", token);
            body.put("op", "result");
            body.put("command_id", commandId);
            String msg = message == null ? "" : message;
            if (msg.length() > Config.MAX_MESSAGE_CHARS) {
                msg = msg.substring(0, Config.MAX_MESSAGE_CHARS) + "\n...[truncated]";
            }
            body.put("message", msg);
            body.put("success", success);
            post(body);
        } catch (Exception ignored) {
        }
    }

    /** POST JSON to the bridge backend. Returns parsed response or null. */
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
            while ((n = is.read(buf)) != -1) {
                if (baos.size() + n > Config.MAX_RESPONSE_BYTES) {
                    return null; // response too large
                }
                baos.write(buf, 0, n);
            }
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

    /**
     * Run a shell command as root via {@code su -c}.
     * Returns combined output prefixed with {@code exit=<code>}.
     */
    private String rootCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
        pb.redirectErrorStream(true);
        Process child = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream is = child.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        });
        reader.start();
        boolean finished = child.waitFor(Config.COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!finished) {
            child.destroy();
            reader.join(2000);
            return "timeout after " + Config.COMMAND_TIMEOUT_SEC + "s\n" + out.toString("UTF-8");
        }
        reader.join(2000);
        int exit = child.exitValue();
        return "exit=" + exit + "\n" + out.toString("UTF-8");
    }

    /**
     * Capture the screen as root, scale it down and save a JPEG at
     * {@link Config#SHOT_JPG}. Returns the path and size (the image itself
     * is too large for the command-result channel).
     */
    private String takeScreenshot(int width) throws Exception {
        String cap = rootCommand("screencap -p " + Config.SHOT_RAW);
        if (!cap.startsWith("exit=0")) return "screencap failed\n" + cap;
        byte[] raw = rootBytes("cat " + Config.SHOT_RAW);
        if (raw == null || raw.length == 0) return "cannot read screenshot";
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (src == null) return "cannot decode screenshot";
        int w = Math.max(120, Math.min(width, src.getWidth()));
        int h = Math.round(src.getHeight() * (w / (float) src.getWidth()));
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(src, w, h, true);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, Config.SHOT_QUALITY, jpg);
        byte[] data = jpg.toByteArray();
        // Write through root so the file is readable by the shell user.
        ProcessBuilder pb = new ProcessBuilder("su", "-c", "cat > " + Config.SHOT_JPG);
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) { os.write(data); }
        p.waitFor(20, TimeUnit.SECONDS);
        return "screenshot ok; path=" + Config.SHOT_JPG + "; bytes=" + data.length
                + "; size=" + w + "x" + h;
    }

    /** Dump the UI hierarchy and return a compact list of visible texts and descriptions. */
    private String uiTree() throws Exception {
        String dump = rootCommand(
                "uiautomator dump /data/local/tmp/devbridge_ui.xml >/dev/null 2>&1; "
                + "grep -o -E 'text=\"[^\"]+\"|content-desc=\"[^\"]+\"' "
                + "/data/local/tmp/devbridge_ui.xml | head -n 300");
        return dump;
    }

    /** Read raw bytes of a file via root. Returns null on failure. */
    private byte[] rootBytes(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            }
            p.waitFor(30, TimeUnit.SECONDS);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void disable() {
        prefs.edit().putBoolean(Config.KEY_ENABLED, false).apply();
        running = false;
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    Config.CHANNEL_ID, "جسر تطوير الهاتف", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, Config.CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("جسر تطوير الهاتف — جلسة التطوير")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true);
        // Stop action (separate from original bridge STOP)
        Intent stop = new Intent(this, BridgeService.class);
        stop.setAction(Config.STOP_ACTION);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                null, "إيقاف", stopPi).build());
        return b.build();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
