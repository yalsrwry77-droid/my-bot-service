package ai.arena.devbridge;

/**
 * Session-specific configuration for the dev bridge.
 *
 * <p>This app is intentionally SEPARATE from the original bridge
 * ({@code ai.arena.bridgecheck} / {@code test-device-pairing}):
 * <ul>
 *   <li>different package: {@code ai.arena.devbridge}</li>
 *   <li>different SharedPreferences file: {@code dev_bridge_session}</li>
 *   <li>different backend slug: {@code dev-session-bridge}</li>
 *   <li>different STOP action: {@code ai.arena.devbridge.STOP}</li>
 * </ul>
 * so development from this session never collides with the existing bridge.
 */
final class Config {
    private Config() {}

    /** Supabase Edge Function URL for THIS session (separate slug). */
    static final String BRIDGE_URL = BuildConfig.BRIDGE_URL;

    /** SharedPreferences file name (separate from original "dev_bridge"). */
    static final String PREFS = "dev_bridge_session";

    static final String KEY_ENABLED = "bridge_enabled";
    static final String KEY_TOKEN = "token";
    static final String KEY_PAIRED = "paired";
    static final String KEY_IDENT = "ident";

    static final String STOP_ACTION = "ai.arena.devbridge.STOP";
    static final String CHANNEL_ID = "development-bridge";
    static final String BRIDGE_VERSION = BuildConfig.BRIDGE_VERSION;

    static final int NOTIFICATION_ID = 1001;
    static final long POLL_INTERVAL_MS = 3000L;
    static final int CONNECT_TIMEOUT_MS = 15000;
    static final int READ_TIMEOUT_MS = 30000;
    static final int COMMAND_TIMEOUT_SEC = 120;
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
}
