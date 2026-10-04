package com.shapkeem.camera.tune;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TuneProcessorTest {
    private static final TuneProcessor.Look NEUTRAL = new TuneProcessor.Look("neutral", 0, 0, 0, 0);

    private static int argb(int r, int g, int b) {
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
    private static int r(int c) { return (c >> 16) & 0xff; }
    private static int g(int c) { return (c >> 8) & 0xff; }
    private static int b(int c) { return c & 0xff; }

    @Test
    public void curveBrightensMidsAndKeepsHighlights() {
        int [] lut = TuneProcessor.buildCurveLut(1.10f, 4.0f);
        assertEquals(0, lut[0]);
        assertEquals(255, lut[255]);
        for(int i=1;i<256;i++)
            assertTrue("monotonic at " + i, lut[i] >= lut[i-1]);
        assertTrue(lut[64] >= 70);
        assertTrue(lut[128] >= 139);
        assertTrue("232 -> " + lut[232], lut[232] < 250);
        assertTrue("250 -> " + lut[250], lut[250] < 255);
    }

    @Test
    public void neutralLookIsIdentity() {
        int [] lut = TuneProcessor.buildLumaLut(NEUTRAL);
        for(int i=0;i<256;i++)
            assertEquals(i, lut[i]);
        int c = argb(200, 120, 40);
        assertEquals(c, TuneProcessor.applyPixel(c, NEUTRAL, lut));
    }

    @Test
    public void lumaLutsAreMonotonicWithFixedEnds() {
        TuneProcessor.Look [] looks = {
                TuneProcessor.LOOK_BRIGHTNESS, TuneProcessor.LOOK_IPHONE,
                new TuneProcessor.Look("max", 20, 30, 15, 30),
                new TuneProcessor.Look("min", -20, -30, -10, -30),
        };
        for(TuneProcessor.Look look : looks) {
            int [] lut = TuneProcessor.buildLumaLut(look);
            assertEquals(look.name, 0, lut[0]);
            assertEquals(look.name, 255, lut[255]);
            for(int i=1;i<256;i++)
                assertTrue(look.name + " monotonic at " + i, lut[i] >= lut[i-1]);
        }
    }

    @Test
    public void contrastDeepensShadowsAndLiftsHighlights() {
        int [] lut = TuneProcessor.buildLumaLut(new TuneProcessor.Look("c", 0, 20, 0, 0));
        assertTrue(lut[64] < 64);
        assertTrue(lut[192] > 192);
        assertTrue(Math.abs(lut[128] - 128) <= 1);
    }

    @Test
    public void saturationKeepsGreyNeutral() {
        TuneProcessor.Look look = new TuneProcessor.Look("s", 0, 0, 0, -30);
        int [] lut = TuneProcessor.buildLumaLut(look);
        int out = TuneProcessor.applyPixel(argb(128, 128, 128), look, lut);
        assertTrue(Math.abs(r(out) - 128) <= 1 && Math.abs(g(out) - 128) <= 1 && Math.abs(b(out) - 128) <= 1);
    }

    @Test
    public void desaturationReducesColourButProtectsSkin() {
        TuneProcessor.Look look = new TuneProcessor.Look("s", 0, 0, 0, -30);
        int [] lut = TuneProcessor.buildLumaLut(look);
        int blue = argb(40, 80, 220);
        int outBlue = TuneProcessor.applyPixel(blue, look, lut);
        assertTrue("blue less saturated", (b(outBlue) - r(outBlue)) < (220 - 40));
        // typical skin tone: changes much less than the blue
        int skin = argb(224, 172, 138);
        int outSkin = TuneProcessor.applyPixel(skin, look, lut);
        int skinChange = Math.abs(r(outSkin) - 224) + Math.abs(g(outSkin) - 172) + Math.abs(b(outSkin) - 138);
        int blueChange = Math.abs(r(outBlue) - 40) + Math.abs(g(outBlue) - 80) + Math.abs(b(outBlue) - 220);
        assertTrue("skin " + skinChange + " < blue " + blueChange, skinChange * 2 < blueChange);
    }

    @Test
    public void warmthPushesGreyTowardsRedAndBlackStaysBlack() {
        TuneProcessor.Look look = new TuneProcessor.Look("w", 0, 0, 10, 0);
        int [] lut = TuneProcessor.buildLumaLut(look);
        int out = TuneProcessor.applyPixel(argb(180, 180, 180), look, lut);
        assertTrue(r(out) > b(out));
        assertEquals(argb(0, 0, 0), TuneProcessor.applyPixel(argb(0, 0, 0), look, lut));
    }
}
