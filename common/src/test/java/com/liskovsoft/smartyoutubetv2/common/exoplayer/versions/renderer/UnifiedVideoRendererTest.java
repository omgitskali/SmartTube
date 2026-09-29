package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.util.MimeTypes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class UnifiedVideoRendererTest {

    @Test
    public void testTrackTypeAndCapabilities() throws Exception {
        UnifiedVideoRenderer unifiedRenderer = new UnifiedVideoRenderer(null, null, null);

        assertEquals("Track type must be C.TRACK_TYPE_VIDEO", C.TRACK_TYPE_VIDEO, unifiedRenderer.getTrackType());
        assertSame("Renderer capabilities must be the instance itself", unifiedRenderer, unifiedRenderer.getCapabilities());
        assertEquals("Initial state must be STATE_DISABLED", Renderer.STATE_DISABLED, unifiedRenderer.getState());
        assertEquals("Adaptive mixed mime type must be ADAPTIVE_NOT_SUPPORTED",
                RendererCapabilities.ADAPTIVE_NOT_SUPPORTED, unifiedRenderer.supportsMixedMimeTypeAdaptation());
    }

    @Test
    public void testSupportsFormatWithNulls() throws Exception {
        UnifiedVideoRenderer unifiedRenderer = new UnifiedVideoRenderer(null, null, null);

        assertEquals("Null format must be FORMAT_UNSUPPORTED_TYPE",
                RendererCapabilities.FORMAT_UNSUPPORTED_TYPE, unifiedRenderer.supportsFormat(null));

        Format av1Format = Format.createVideoSampleFormat(
                "av1", MimeTypes.VIDEO_AV1, null, Format.NO_VALUE, Format.NO_VALUE,
                1920, 1080, 30.0f, null, null);

        assertEquals("Without inner renderers, AV1 must return unsupported",
                RendererCapabilities.FORMAT_UNSUPPORTED_TYPE, unifiedRenderer.supportsFormat(av1Format));
    }

    @Test
    public void testHandleMessageWithNulls() throws Exception {
        UnifiedVideoRenderer unifiedRenderer = new UnifiedVideoRenderer(null, null, null);
        // Ensure handleMessage doesn't throw NullPointerException when inner renderers are null
        unifiedRenderer.handleMessage(C.MSG_SET_SURFACE, null);
        unifiedRenderer.handleMessage(C.MSG_SET_VIDEO_FRAME_METADATA_LISTENER, null);
        unifiedRenderer.disable();
        unifiedRenderer.reset();
    }
}
