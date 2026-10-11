package com.core.gl;

/** Host-independent status values; native bridges retain the stable numeric protocol. */
public final class GraphicsStatus {
    public static final int READY = 0, MISSING_ABI = 1, GLES3_UNAVAILABLE = 2,
            NATIVE_FAILED = 3, FILTER_FAILED = 4, WAITING = 5, OFFICIAL_UNSUPPORTED = 6, SIZE_UNAVAILABLE = 7;
    private GraphicsStatus() {}
}
