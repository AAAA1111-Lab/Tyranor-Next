package com.core.gl;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import com.core.engine.LaunchContract;

/** KR2 runtime configuration; legacy single-mode callers remain supported. */
public final class Anime4kRuntime {
    public static final String EXTRA_MODE = "tyranor_anime4k";
    public static final String MODE_OFF = "off", MODE_S = "s", MODE_M = "m", MODE_L = "l",
            MODE_SOFT_S = "soft_s", MODE_SOFT_M = "soft_m", MODE_SOFT_L = "soft_l", MODE_DEBLUR = "deblur";
    private static volatile boolean enabled, requested, rawInput, analyzing;
    private static volatile GraphicsFilterChain chain = GraphicsFilterChain.parse("v1:");
    private static volatile int status = GraphicsStatus.WAITING;
    private static final ArtemisGraphicsStats STATS = new ArtemisGraphicsStats();
    private Anime4kRuntime() {}

    public static void configure(Context context, String mode) {
        apply(context, GraphicsFilterChain.fromLegacy(mode), true);
    }

    public static void configureFromIntent(Context context, Intent intent) {
        if (intent != null && intent.hasExtra(LaunchContract.GRAPHICS_FILTER_CHAIN)) {
            apply(context, GraphicsFilterChain.parse(intent.getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN)),
                    intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false));
            analyzing = enabled && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false);
        } else {
            configure(context, intent == null ? null : intent.getStringExtra(EXTRA_MODE));
        }
    }

    private static void apply(Context context, GraphicsFilterChain configured, boolean master) {
        boolean invalid = configured == null;
        chain = invalid ? GraphicsFilterChain.parse("v1:") : configured;
        requested = !invalid && master && chain.hasActive();
        analyzing = false;
        enabled = requested && supportsGles3(context);
        status = invalid ? GraphicsStatus.FILTER_FAILED
                : requested && !enabled ? GraphicsStatus.GLES3_UNAVAILABLE : GraphicsStatus.WAITING;
        STATS.reset();
    }

    private static boolean supportsGles3(Context context) {
        if (context == null) return false;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }
    public static ArtemisPerformanceOverlay.Source source() {
        return new ArtemisPerformanceOverlay.Source() {
            public ArtemisGraphicsStats stats() { return Anime4kRuntime.stats(); }
            public boolean isAnalyzing() { return Anime4kRuntime.isAnalyzing(); }
            public int status() { return Anime4kRuntime.status(); }
        };
    }

    public static boolean isEnabled() { return enabled; }
    public static boolean useGles3Context() { return enabled; }
    public static boolean isAnalyzing() { return analyzing; }
    /** Compatibility alias for existing bridge callers; only the analyzer requests counters. */
    public static boolean isMonitoring() { return analyzing; }
    public static GraphicsFilterChain chain() { return chain; }
    public static int status() { return status; }
    public static ArtemisGraphicsStats stats() { return STATS; }
    public static String shaderAsset() {
        for (int i = 0; i < chain.steps().size(); i++) {
            String asset = GraphicsFilterChain.shaderAsset(chain.steps().get(i).id);
            if (asset != null) return asset;
        }
        return "Anime4K_Restore_CNN_S";
    }
    public static void onContextCreated() {
        STATS.reset();
        if (enabled) status = GraphicsStatus.READY;
    }
    public static void onFrame(int width, int height, GraphicsFilterPipeline pipeline) {
        if (pipeline != null) status = pipeline.isFailed() ? GraphicsStatus.FILTER_FAILED
                : pipeline.bypassReason() == Anime4kPostProcessor.BYPASS_NONE ? GraphicsStatus.READY
                : GraphicsStatus.SIZE_UNAVAILABLE;
        if (analyzing) {
            STATS.surface(width, height);
            STATS.frame(width, height, pipeline == null ? width : pipeline.outputWidth(),
                    pipeline == null ? height : pipeline.outputHeight(), width, height);
            STATS.gpu(-1); // KR2 has no non-blocking GPU timer callback in its Surface adapter.
            STATS.presented();
        }
    }
    public static boolean hasRawInput() { return rawInput; }
    static void setRawInput(boolean raw) { rawInput = raw; }
    static void onRawFrame(int sw,int sh,int width,int height,GraphicsFilterPipeline processor) {
        status = sw <= 0 || sh <= 0 ? GraphicsStatus.SIZE_UNAVAILABLE
                : processor != null && processor.isFailed() ? GraphicsStatus.FILTER_FAILED
                : processor != null && processor.bypassReason() != Anime4kPostProcessor.BYPASS_NONE
                ? GraphicsStatus.SIZE_UNAVAILABLE : GraphicsStatus.READY;
        if (analyzing) {
            GraphicsOutputSize.Size fitted = GraphicsOutputSize.fit(sw,sh,width,height);
            STATS.surface(width,height);
            STATS.frame(sw,sh,processor == null ? sw : processor.outputWidth(),
                    processor == null ? sh : processor.outputHeight(),
                    fitted == null ? 0 : fitted.width,fitted == null ? 0 : fitted.height);
            STATS.gpu(-1); STATS.presented();
        }
    }
    public static void reset() {
        enabled = requested = rawInput = analyzing = false;
        chain = GraphicsFilterChain.parse("v1:");
        status = GraphicsStatus.WAITING;
        STATS.reset();
    }
}
