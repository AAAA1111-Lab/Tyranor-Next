package com.core.gl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Surface;
import com.core.engine.LaunchContract;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Optional host-owned producer for fixed official libraries. No clean-engine callbacks. */
public final class ArtemisOfficialBridge implements ArtemisPerformanceOverlay.Source {
    private static final ArtemisOfficialBridge INSTANCE = new ArtemisOfficialBridge();
    private static final Map<String, String> LIBRARIES;
    static {
        Map<String, String> hashes = new HashMap<>();
        hashes.put("libartemis.so", "cb2b58d8080b29529b27a8453f602646ab5f13ae5d4acc59b673c27c27e2d9cc");
        hashes.put("libartemis-compatible-v2.so", "75ffde0ad3a8c58fd82a3f797131686eb66ed7468a7db6452be7932718c44b35");
        hashes.put("libartemis-v4.so", "9839e6fee3287ac7ad16149b49732b87b51276c3dff2a0633bdf7d2247807012");
        hashes.put("libartemis-v5.so", "55d8deb7623b968ce6d9cb290e33a1199bc978fe72583fc9403d180afaf4b315");
        hashes.put("libartemis-v6.so", "5d6c9d2c6ce0e6010f4e783a4dfe01961a8cf2643e0306b47492cfbbfc6f2d9d");
        LIBRARIES = Collections.unmodifiableMap(hashes);
    }
    private static Context context;
    private static GraphicsFilterChain chain;
    private static volatile boolean requested, analyzer;
    private static volatile int state = GraphicsStatus.WAITING;
    private static volatile ArtemisOfficialSurfaceProxy proxy;
    private final ArtemisGraphicsStats empty = new ArtemisGraphicsStats();
    private ArtemisOfficialBridge() {}

    /** Called before NativeActivity starts its worker. JNI then owns connection changes. */
    public static void prepare(Activity activity) {
        Intent intent = activity.getIntent();
        String library = intent == null ? null : intent.getStringExtra(LaunchContract.ENGINE_LIB_NAME);
        context = activity.getApplicationContext();
        chain = intent == null ? null : GraphicsFilterChain.parse(intent.getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        requested = library != null && LIBRARIES.containsKey("lib" + library + ".so")
                && chain != null && chain.hasActive()
                && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false);
        analyzer = requested && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false);
        state = GraphicsStatus.WAITING;
        INSTANCE.empty.reset();
    }
    public static ArtemisPerformanceOverlay.Source source() { return INSTANCE; }
    public static boolean verifyLibrary(String path) {
        if (!requested || path == null) return false;
        try {
            File file = new File(path).getCanonicalFile();
            String expected = LIBRARIES.get(file.getName());
            if (expected == null) {
                state = GraphicsStatus.NATIVE_FAILED;
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            boolean verified = expected.contentEquals(actual);
            if (!verified) state = GraphicsStatus.NATIVE_FAILED;
            return verified;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            state = GraphicsStatus.NATIVE_FAILED; return false;
        }
    }
    /** Render-thread call; GLES preparation never needs an Activity-thread callback. */
    public static synchronized Surface connect(Surface screen, int width, int height, int producerWidth, int producerHeight) {
        if (!requested || screen == null) return null;
        try {
            if (proxy == null) proxy = new ArtemisOfficialSurfaceProxy(context, chain, analyzer);
            return proxy.connect(screen, width, height, producerWidth, producerHeight);
        } catch (RuntimeException failure) {
            state = GraphicsStatus.NATIVE_FAILED;
            android.util.Log.w("ArtemisOfficial", "Surface proxy unavailable; keeping direct output", failure);
            // Keep the instance until native confirms its output has disconnected.
            // A timed-out connect job may still be running on the GLES thread.
            return null;
        }
    }
    /** Set the consumer's actual default buffer size before the official resize/reset. */
    public static boolean resizeProducer(int width, int height) {
        ArtemisOfficialSurfaceProxy current = proxy;
        return current != null && current.resizeProducer(width, height);
    }
    public static void submitted(long timestamp, int width, int height, boolean original) {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) current.submitted(timestamp, width, height, original);
    }
    public static boolean healthy() { ArtemisOfficialSurfaceProxy current = proxy; return current != null && !current.failed(); }
    /** Native detaches its EGL producer before dropping either Surface reference. */
    public static synchronized boolean disconnect() {
        ArtemisOfficialSurfaceProxy current = proxy;
        return current == null || current.disconnect();
    }
    public static void nativeStatus(int status) { state = status; }
    /** Called after NativeActivity.onDestroy joined its worker. */
    public static synchronized void close() {
        if (proxy != null) proxy.release();
        proxy = null; requested = analyzer = false;
    }
    @Override public ArtemisGraphicsStats stats() { ArtemisOfficialSurfaceProxy current = proxy; return current == null ? empty : current.stats(); }
    @Override public boolean isAnalyzing() { return requested && analyzer; }
    @Override public int status() { ArtemisOfficialSurfaceProxy current = proxy; return current == null ? state : current.status(); }
}
