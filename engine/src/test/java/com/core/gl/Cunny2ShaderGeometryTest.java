package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class Cunny2ShaderGeometryTest {
    @Test public void cunnyRetainsNativeTwoTimesOutputForDefaultDisplayScaling() {
        GraphicsFilterPlan phone = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:cunny_fast_nvl,s"),
                1600, 900, 2608, 1200);
        assertEquals(3200, phone.outputWidths[0]); assertEquals(1800, phone.outputHeights[0]);
        assertEquals(3200, phone.inputWidths[1]);
        GraphicsShaderGeometry nativePhone = new GraphicsShaderGeometry(1600, 900, 2133, 1200, 2, false, 1);
        assertEquals(3200, nativePhone.width); assertEquals(1800, nativePhone.height);
        LanczosScale.Plan resample = LanczosScale.resample(nativePhone.width, nativePhone.height, 2133, 1200);
        assertTrue(resample.horizontal); assertTrue(resample.vertical);
        GraphicsFilterPlan tablet = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:cunny_3x12_nvl"),
                1280, 720, 2560, 1600);
        assertEquals(2560, tablet.outputWidths[0]); assertEquals(1440, tablet.outputHeights[0]);
        GraphicsShaderGeometry nativeTablet = new GraphicsShaderGeometry(1280, 720, 2560, 1440, 2, false, 1);
        assertFalse(LanczosScale.resample(nativeTablet.width, nativeTablet.height, 2560, 1440).horizontal);
        assertFalse(LanczosScale.resample(nativeTablet.width, nativeTablet.height, 2560, 1440).vertical);
    }

    @Test public void orderedScaleNodesRespectPauseIdentityAndSmallerScreens() {
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:!cunny_fast_nvl,lanczos,cunny_8x32_nvl,s,lanczos"),
                1280, 720, 2560, 1600);
        assertFalse(plan.execute[0]); assertTrue(plan.execute[1]); assertTrue(plan.execute[2]);
        assertTrue(plan.execute[3]); assertTrue(plan.execute[4]);
        assertEquals(5120, plan.inputWidths[3]);
        assertEquals(2560, plan.outputWidths[4]);
        GraphicsFilterPlan smaller = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:cunny_fast_nvl,s"),
                1280, 720, 800, 600);
        assertTrue(smaller.execute[0]); assertTrue(smaller.execute[1]);
        assertEquals(2560, smaller.outputWidths[1]);
    }

    @Test public void overscreenCunnyDoesNotAllocateImplicitDownsamplingTargets() {
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:cunny_fast_nvl"), 1600, 900, 2608, 1200);
        GraphicsShaderGeometry cnn = new GraphicsShaderGeometry(1600, 900, plan.outputWidths[0], plan.outputHeights[0], 2, false, 1);
        assertEquals(1600L * 900 * 8 * 16 + 3200L * 1800 * 4,
                cnn.estimate(1600, 900, plan.outputWidths[0], plan.outputHeights[0], 16, 0, false));
        assertEquals(2133, plan.fitted.width); assertEquals(1200, plan.fitted.height);
    }
}
