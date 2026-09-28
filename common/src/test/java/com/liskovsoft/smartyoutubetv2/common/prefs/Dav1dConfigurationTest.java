package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.os.Build;
import com.google.android.exoplayer2.ext.dav1d.Dav1dLibrary;
import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Dav1dConfigurationTest {

    @BeforeClass
    public static void initAndroidStubs() {
        try {
            Field deviceField = Build.class.getField("DEVICE");
            deviceField.setAccessible(true);
            deviceField.set(null, "generic");
        } catch (Throwable ignored) {}
        try {
            Field modelField = Build.class.getField("MODEL");
            modelField.setAccessible(true);
            modelField.set(null, "generic");
        } catch (Throwable ignored) {}
    }

    @Before
    public void setUp() {
        // Reset state
        Dav1dLibrary.setEnabled(true);
        Dav1dLibrary.setMaxHeight(1080);
        DeviceHelpers.setSoftwareAV1MaxHeight(1080);
    }

    @Test
    public void testDefaultDav1dLibraryState() {
        assertTrue("Dav1dLibrary should be enabled by default", Dav1dLibrary.isEnabled());
        assertEquals("Default software max height should be 1080", 1080, Dav1dLibrary.getMaxHeight());
        assertEquals("DeviceHelpers software max height should be 1080", 1080, DeviceHelpers.getSoftwareAV1MaxHeight());
    }

    @Test
    public void testResolutionCappingAt1080p() {
        Dav1dLibrary.setEnabled(true);
        Dav1dLibrary.setMaxHeight(1080);
        DeviceHelpers.setSoftwareAV1MaxHeight(1080);

        assertTrue("480p AV1 should be supported", DeviceHelpers.isAV1ResolutionSupported(480));
        assertTrue("720p AV1 should be supported", DeviceHelpers.isAV1ResolutionSupported(720));
        assertTrue("1080p AV1 should be supported", DeviceHelpers.isAV1ResolutionSupported(1080));
        assertFalse("1440p AV1 should NOT be supported under 1080p cap", DeviceHelpers.isAV1ResolutionSupported(1440));
        assertFalse("4K AV1 should NOT be supported under 1080p cap", DeviceHelpers.isAV1ResolutionSupported(2160));
        assertFalse("8K AV1 should NOT be supported under 1080p cap", DeviceHelpers.isAV1ResolutionSupported(4320));
    }

    @Test
    public void testUnlock4KDav1d() {
        Dav1dLibrary.setEnabled(true);
        Dav1dLibrary.setMaxHeight(4320);
        DeviceHelpers.setSoftwareAV1MaxHeight(4320);

        assertEquals("Dav1dLibrary max height should be 4320", 4320, Dav1dLibrary.getMaxHeight());
        assertEquals("DeviceHelpers software max height should be 4320", 4320, DeviceHelpers.getSoftwareAV1MaxHeight());

        assertTrue("1080p AV1 should be supported", DeviceHelpers.isAV1ResolutionSupported(1080));
        assertTrue("1440p AV1 should be supported when 4K is unlocked", DeviceHelpers.isAV1ResolutionSupported(1440));
        assertTrue("4K AV1 should be supported when 4K is unlocked", DeviceHelpers.isAV1ResolutionSupported(2160));
        assertTrue("8K AV1 should be supported when 4K is unlocked", DeviceHelpers.isAV1ResolutionSupported(4320));
    }

    @Test
    public void testDisableDav1d() {
        Dav1dLibrary.setEnabled(false);
        DeviceHelpers.setSoftwareAV1MaxHeight(-1);

        assertFalse("Dav1dLibrary should be disabled", Dav1dLibrary.isEnabled());
        assertEquals("DeviceHelpers software max height should be -1", -1, DeviceHelpers.getSoftwareAV1MaxHeight());
        assertFalse("1080p AV1 should NOT be supported when software decoder is disabled", DeviceHelpers.isAV1ResolutionSupported(1080));
    }
}
