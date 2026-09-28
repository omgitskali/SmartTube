package com.liskovsoft.smartyoutubetv2.common.prefs;

import com.google.android.exoplayer2.ext.dav1d.Dav1dLibrary;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Dav1dConfigurationTest {

    @Before
    public void setUp() {
        // Reset state
        Dav1dLibrary.setEnabled(true);
        Dav1dLibrary.setMaxHeight(1080);
    }

    @Test
    public void testDefaultDav1dLibraryState() {
        assertTrue("Dav1dLibrary should be enabled by default", Dav1dLibrary.isEnabled());
        assertEquals("Default software max height should be 1080", 1080, Dav1dLibrary.getMaxHeight());
    }

    @Test
    public void testResolutionCappingAt1080p() {
        Dav1dLibrary.setEnabled(true);
        Dav1dLibrary.setMaxHeight(1080);

        assertTrue("480p AV1 should be supported", Dav1dLibrary.isResolutionSupported(480));
        assertTrue("720p AV1 should be supported", Dav1dLibrary.isResolutionSupported(720));
        assertTrue("1080p AV1 should be supported", Dav1dLibrary.isResolutionSupported(1080));
        assertFalse("1440p AV1 should NOT be supported under 1080p cap", Dav1dLibrary.isResolutionSupported(1440));
        assertFalse("4K AV1 should NOT be supported under 1080p cap", Dav1dLibrary.isResolutionSupported(2160));
        assertFalse("8K AV1 should NOT be supported under 1080p cap", Dav1dLibrary.isResolutionSupported(4320));
    }

    @Test
    public void testUnlock4KDav1d() {
        Dav1dLibrary.setEnabled(true);
        Dav1dLibrary.setMaxHeight(4320);

        assertEquals("Dav1dLibrary max height should be 4320", 4320, Dav1dLibrary.getMaxHeight());

        assertTrue("1080p AV1 should be supported", Dav1dLibrary.isResolutionSupported(1080));
        assertTrue("1440p AV1 should be supported when 4K is unlocked", Dav1dLibrary.isResolutionSupported(1440));
        assertTrue("4K AV1 should be supported when 4K is unlocked", Dav1dLibrary.isResolutionSupported(2160));
        assertTrue("8K AV1 should be supported when 4K is unlocked", Dav1dLibrary.isResolutionSupported(4320));
    }

    @Test
    public void testDisableDav1d() {
        Dav1dLibrary.setEnabled(false);

        assertFalse("Dav1dLibrary should be disabled", Dav1dLibrary.isEnabled());
        assertFalse("1080p AV1 should NOT be supported when software decoder is disabled", Dav1dLibrary.isResolutionSupported(1080));
        assertFalse("4K AV1 should NOT be supported when software decoder is disabled", Dav1dLibrary.isResolutionSupported(2160));
    }
}
