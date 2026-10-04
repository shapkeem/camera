package com.shapkeem.camera.tune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TuneProcessorTest {
    @Test
    public void lutScalesAndClamps() {
        int [] lut = TuneProcessor.buildLut(1.10f);
        assertEquals(256, lut.length);
        assertEquals(0, lut[0]);
        assertEquals(110, lut[100]);
        assertEquals(255, lut[232]); // 232*1.1 = 255.2
        assertEquals(255, lut[255]);
        for(int i=1;i<256;i++)
            assertTrue("monotonic at " + i, lut[i] >= lut[i-1]);
    }

    @Test
    public void identityGainIsNoOp() {
        int [] lut = TuneProcessor.buildLut(1.0f);
        for(int i=0;i<256;i++)
            assertEquals(i, lut[i]);
    }

    @Test
    public void curveBrightensMidsAndKeepsHighlights() {
        int [] lut = TuneProcessor.buildCurveLut(1.10f, 4.0f);
        assertEquals(0, lut[0]);
        assertEquals(255, lut[255]);
        for(int i=1;i<256;i++)
            assertTrue("monotonic at " + i, lut[i] >= lut[i-1]);
        // shadows/mids get close to the full +10%
        assertTrue(lut[64] >= 70);
        assertTrue(lut[128] >= 139);
        // highlights that the plain gain would clip stay below white
        assertTrue("232 -> " + lut[232], lut[232] < 250);
        assertTrue("250 -> " + lut[250], lut[250] < 255);
    }
}
