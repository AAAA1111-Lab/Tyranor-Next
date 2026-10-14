package com.core.gl;

import android.opengl.GLES30;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.function.IntSupplier;

import static org.junit.Assert.*;

public class Anime4kGlErrorBoundaryTest {
    @Test public void allocationPressureDoesNotLeakIntoHostRestoration() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(Anime4kPostProcessor.TargetUnavailableException.class,
                () -> Anime4kPostProcessor.requireGlSuccess("render target allocation", true, errors));
        assertTrue(errors.pending.isEmpty());

        // The bypass can release partial targets and restore the host without
        // promoting the original allocation rejection into a permanent failure.
        Anime4kPostProcessor.requireGlSuccess("render target cleanup", false, errors);
        Anime4kPostProcessor.requireGlSuccess("filter state restoration", false, errors);
        // A later allocation (for changed geometry) starts with a clean boundary.
        Anime4kPostProcessor.requireGlSuccess("render target allocation", true, errors);
    }

    @Test public void genuineRestorationFailureIsStillPermanent() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY);
        assertThrows(Anime4kPostProcessor.TargetUnavailableException.class,
                () -> Anime4kPostProcessor.requireGlSuccess("render target allocation", true, errors));
        errors.pending.add(GLES30.GL_INVALID_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> Anime4kPostProcessor.requireGlSuccess("filter state restoration", false, errors));
        assertTrue(errors.pending.isEmpty());
    }

    @Test public void invalidAllocationApiUseIsNotMaskedByMemoryPressure() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY, GLES30.GL_INVALID_ENUM,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> Anime4kPostProcessor.requireGlSuccess("render target allocation", true, errors));
        assertTrue(errors.pending.isEmpty());
    }

    @Test public void drawingErrorsDrainAllFlagsAndRemainPermanent() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> Anime4kPostProcessor.requireGlSuccess("filter node drawing", false, errors));
        assertTrue(errors.pending.isEmpty());
        Anime4kPostProcessor.requireGlSuccess("filter state restoration", false, errors);
    }

    @Test public void unclearedContextIsBoundedAndNeverRetryable() {
        int[] reads = {0};
        assertThrows(IllegalStateException.class,
                () -> Anime4kPostProcessor.requireGlSuccess("render target allocation", true,
                        () -> { reads[0]++; return GLES30.GL_OUT_OF_MEMORY; }));
        assertTrue(reads[0] > 0 && reads[0] <= 16);
    }

    private static final class ErrorQueue implements IntSupplier {
        final ArrayDeque<Integer> pending = new ArrayDeque<>();
        ErrorQueue(int... errors) { for (int error : errors) pending.add(error); }
        @Override public int getAsInt() {
            return pending.isEmpty() ? GLES30.GL_NO_ERROR : pending.removeFirst();
        }
    }
}
