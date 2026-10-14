package com.core.gl;

import java.util.concurrent.CountDownLatch;

/** Result of one queued consumer detachment, including completion after a timeout. */
final class ArtemisOfficialDisconnect {
    // JNI contract: keep values aligned with DisconnectResult in artemis_official_graphics.cpp.
    static final int DETACHED = 0;
    static final int PENDING = 1;
    static final int FAILED = 2;

    final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean detached;

    synchronized void complete(boolean detached) {
        if (done.getCount() == 0) return;
        this.detached = detached;
        done.countDown();
    }

    int result() {
        if (done.getCount() != 0) return PENDING;
        return detached ? DETACHED : FAILED;
    }
}
