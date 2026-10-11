package com.core.siglus;

/** Optional downstream C ABI; old engine libraries continue through NativeSiglus. */
final class SiglusStageBridge {
    static { System.loadLibrary("siglus_bridge"); }
    private SiglusStageBridge() {}

    /** logical W/H, configured Surface W/H, viewport X/Y/W/H, render serial low/high. Engine thread only. */
    static native boolean geometry(long handle, int[] outGeometry);

}
