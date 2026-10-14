package com.core.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class ArtemisOfficialDisconnectTest {
    @Test
    public void workerTimeoutRemainsPendingUntilTheSameJobDetaches() throws Exception {
        ArtemisOfficialDisconnect request = new ArtemisOfficialDisconnect();
        CountDownLatch resume = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                resume.await();
                request.complete(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                request.complete(false);
            }
        });
        worker.start();
        try {
            // The caller timed out, while the consumer still owns its EGL queue.
            assertFalse(request.done.await(0, TimeUnit.SECONDS));
            assertEquals(ArtemisOfficialDisconnect.PENDING, request.result());
            assertEquals(ArtemisOfficialDisconnect.PENDING, request.result());
            resume.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertEquals(ArtemisOfficialDisconnect.DETACHED, request.result());
        } finally {
            resume.countDown();
            worker.join(2000);
            if (worker.isAlive()) worker.interrupt();
        }
    }

    @Test
    public void completedDriverFailureIsDistinctFromAnUnfinishedJob() {
        ArtemisOfficialDisconnect request = new ArtemisOfficialDisconnect();
        assertEquals(ArtemisOfficialDisconnect.PENDING, request.result());
        request.complete(false);
        assertEquals(ArtemisOfficialDisconnect.FAILED, request.result());
    }

    @Test
    public void laterCleanupCannotRevokeAConfirmedDetachment() {
        ArtemisOfficialDisconnect request = new ArtemisOfficialDisconnect();
        request.complete(true);
        request.complete(false);
        assertEquals(ArtemisOfficialDisconnect.DETACHED, request.result());
    }
}
