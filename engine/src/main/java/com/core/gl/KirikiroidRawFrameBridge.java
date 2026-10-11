package com.core.gl;

import android.content.Context;
import androidx.annotation.Keep;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Leases a processed game-window texture to Cocos for one nativeRender call. */
@Keep
public final class KirikiroidRawFrameBridge {
    /** Internal result codes; the UI maps these values to localized resources. */
    public enum InstallStatus {
        DISABLED, UNCONFIGURED, UNSUPPORTED_LIBRARY, HASH_MISMATCH, LIBRARY_READ_FAILED,
        WAITING_FOR_CONTEXT, INSTALLED, NATIVE_UNAVAILABLE, NATIVE_ARGUMENT,
        NATIVE_LIBRARY_LOOKUP, NATIVE_IMAGE_LOOKUP, NATIVE_BUILD_ID, NATIVE_SYMBOLS,
        NATIVE_VTABLE, NATIVE_RELOCATION, NATIVE_CALLBACK, NATIVE_PROTECTION,
        NATIVE_ROLLBACK, NATIVE_UNKNOWN
    }
    private static volatile InstallStatus installStatus = InstallStatus.UNCONFIGURED;
    private static String verifiedLibrary;
    private static boolean installed, frameSeen;
    private static GraphicsFilterPipeline pipeline;
    private KirikiroidRawFrameBridge() {}

    /** Runs during library loading, before the GL renderer starts. */
    public static void configureLibrary(String library) {
        verifiedLibrary = null;
        installStatus = InstallStatus.UNCONFIGURED;
        if (!Anime4kRuntime.isEnabled()) {
            installStatus = InstallStatus.DISABLED;
            return;
        }
        if (library == null || library.isEmpty()) return;
        try {
            // The Android linker resolves filesystem aliases when recording
            // loaded images. Verify the same canonical file before install.
            String canonicalLibrary = new File(library).getCanonicalPath();
            String expected = canonicalLibrary.endsWith("/libgame.so")
                    ? "05e2ff4c77f1561608ad7703153d2fb09855bf223237a85dc2267fff1388564f"
                    : canonicalLibrary.endsWith("/libgame134.so")
                    ? "71f616c68db94caa7afccc60a20ba7ea48cbb562c762dbd91dd533593b5de238" : null;
            if (expected == null) {
                installStatus = InstallStatus.UNSUPPORTED_LIBRARY;
                return;
            }
            try (FileInputStream input = new FileInputStream(canonicalLibrary)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                StringBuilder hex = new StringBuilder(64);
                for (byte b : digest.digest()) hex.append(Character.forDigit((b >>> 4) & 15,16)).append(Character.forDigit(b & 15,16));
                if (expected.contentEquals(hex)) {
                    verifiedLibrary = canonicalLibrary;
                    installStatus = InstallStatus.WAITING_FOR_CONTEXT;
                } else installStatus = InstallStatus.HASH_MISMATCH;
            }
        } catch (Exception unavailable) {
            verifiedLibrary = null;
            installStatus = InstallStatus.LIBRARY_READ_FAILED;
        }
    }
    public static InstallStatus installStatus() { return installStatus; }
    private static InstallStatus statusForStage(int stage) {
        switch (stage) {
            case 0: return InstallStatus.INSTALLED;
            case 1: return InstallStatus.NATIVE_ARGUMENT;
            case 2: return InstallStatus.NATIVE_LIBRARY_LOOKUP;
            case 3: return InstallStatus.NATIVE_IMAGE_LOOKUP;
            case 4: return InstallStatus.NATIVE_BUILD_ID;
            case 5: return InstallStatus.NATIVE_SYMBOLS;
            case 6: return InstallStatus.NATIVE_VTABLE;
            case 7: return InstallStatus.NATIVE_RELOCATION;
            case 8: return InstallStatus.NATIVE_CALLBACK;
            case 9: return InstallStatus.NATIVE_PROTECTION;
            case 10: return InstallStatus.NATIVE_ROLLBACK;
            default: return InstallStatus.NATIVE_UNKNOWN;
        }
    }
    public static void onContextCreated(Context context) {
        if (pipeline != null) pipeline.abandonContext();
        pipeline = null;
        installed = false;
        if (!Anime4kRuntime.isEnabled()) installStatus = InstallStatus.DISABLED;
        else if (verifiedLibrary != null) {
            try {
                installStatus = statusForStage(nativeInstall(verifiedLibrary));
                installed = installStatus == InstallStatus.INSTALLED;
            } catch (UnsatisfiedLinkError unavailable) {
                installStatus = InstallStatus.NATIVE_UNAVAILABLE;
            }
        }
        if (installed) {
            nativeResetContext();
            pipeline = new GraphicsFilterPipeline(context, Anime4kRuntime.chain());
            if (Anime4kRuntime.isAnalyzing()) pipeline.setProfiler(Anime4kRuntime.stats(), Anime4kRuntime.isAnalyzing());
        }
        Anime4kRuntime.setRawInput(installed);
    }
    public static boolean isInstalled() { return installed; }
    public static void beginFrame(int width, int height) {
        frameSeen = false;
        if (installed && Anime4kRuntime.isEnabled()) nativeBeginFrame(width, height);
    }
    public static void endFrame(int width, int height) {
        if (!installed) return;
        nativeEndFrame();
        if (!frameSeen) Anime4kRuntime.onRawFrame(0,0,width,height,null);
    }
    @Keep
    private static int process(int texture,int sourceWidth,int sourceHeight,float u0,float v0,
                               float u1,float v1,int width,int height) {
        if (pipeline == null || !Anime4kRuntime.isEnabled()) return 0;
        int output = pipeline.renderCroppedTexture(texture,sourceWidth,sourceHeight,u0,v0,u1,v1,width,height);
        frameSeen = true;
        Anime4kRuntime.onRawFrame(sourceWidth,sourceHeight,width,height,pipeline);
        return output;
    }
    public static void release() {
        if (installed) nativeResetContext();
        // The host preserves its EGL context on pause. Keep this reusable
        // pipeline object so resume can allocate fresh targets on demand.
        if (pipeline != null) pipeline.release();
    }
    private static native int nativeInstall(String library);
    private static native void nativeBeginFrame(int width,int height);
    private static native void nativeEndFrame();
    private static native void nativeResetContext();
}