package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.RendererConfiguration;
import com.google.android.exoplayer2.ext.dav1d.Dav1dLibrary;
import com.google.android.exoplayer2.ext.dav1d.Libdav1dVideoRenderer;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.util.MediaClock;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoFrameMetadataListener;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.io.IOException;

/**
 * Composite video renderer that wraps both {@link MediaCodecVideoRenderer} and
 * {@link Libdav1dVideoRenderer} into a single ExoPlayer video renderer registered at index 0.
 *
 * This maintains SmartTube's fixed 3-renderer architecture (0=Video, 1=Audio, 2=Subtitle)
 * while providing seamless fallback to libdav1d software decoding for AV1 streams
 * on hardware lacking AV1 hardware decoders.
 */
public class UnifiedVideoRenderer implements Renderer, RendererCapabilities {
    private static final String TAG = UnifiedVideoRenderer.class.getSimpleName();

    private final MediaCodecVideoRenderer mMediaCodecRenderer;
    private final Libdav1dVideoRenderer mDav1dRenderer;
    private final PlayerTweaksData mPlayerTweaksData;

    private Renderer mActiveRenderer;
    private Surface mSurface;
    private VideoFrameMetadataListener mFrameMetadataListener;
    private RendererConfiguration mConfiguration;
    private Format[] mStreamFormats;
    private SampleStream mStream;
    private long mPositionUs;
    private long mOffsetUs;
    private int mIndex;
    private int mState = STATE_DISABLED;
    private float mOperatingRate = 1f;

    public UnifiedVideoRenderer(MediaCodecVideoRenderer mediaCodecRenderer,
                                @Nullable Libdav1dVideoRenderer dav1dRenderer,
                                @Nullable PlayerTweaksData playerTweaksData) {
        mMediaCodecRenderer = mediaCodecRenderer;
        mDav1dRenderer = dav1dRenderer;
        mPlayerTweaksData = playerTweaksData;
    }

    // ==========================================
    // Renderer Implementation
    // ==========================================

    @Override
    public int getTrackType() {
        return C.TRACK_TYPE_VIDEO;
    }

    @Override
    public RendererCapabilities getCapabilities() {
        return this;
    }

    @Override
    public void setIndex(int index) {
        mIndex = index;
        if (mMediaCodecRenderer != null) {
            mMediaCodecRenderer.setIndex(index);
        }
        if (mDav1dRenderer != null) {
            mDav1dRenderer.setIndex(index);
        }
    }

    @Override
    public MediaClock getMediaClock() {
        return mActiveRenderer != null ? mActiveRenderer.getMediaClock() : null;
    }

    @Override
    public int getState() {
        return mActiveRenderer != null ? mActiveRenderer.getState() : mState;
    }

    @Override
    public void enable(RendererConfiguration configuration, Format[] formats, SampleStream stream,
                       long positionUs, boolean joining, long offsetUs) throws ExoPlaybackException {
        mConfiguration = configuration;
        mStreamFormats = formats;
        mStream = stream;
        mPositionUs = positionUs;
        mOffsetUs = offsetUs;
        mState = STATE_ENABLED;

        Renderer target = selectRenderer(formats);
        mActiveRenderer = target;

        Log.i(TAG, "enable(): using %s", (mActiveRenderer != null ? mActiveRenderer.getClass().getSimpleName() : "null"));

        if (mActiveRenderer != null) {
            if (mSurface != null) {
                mActiveRenderer.handleMessage(C.MSG_SET_SURFACE, mSurface);
            }
            if (mFrameMetadataListener != null) {
                mActiveRenderer.handleMessage(C.MSG_SET_VIDEO_FRAME_METADATA_LISTENER, mFrameMetadataListener);
            }
            mActiveRenderer.enable(configuration, formats, stream, positionUs, joining, offsetUs);
            if (mOperatingRate != 1f) {
                mActiveRenderer.setOperatingRate(mOperatingRate);
            }
        }
    }

    @Override
    public void start() throws ExoPlaybackException {
        mState = STATE_STARTED;
        if (mActiveRenderer != null) {
            mActiveRenderer.start();
        }
    }

    @Override
    public void replaceStream(Format[] formats, SampleStream stream, long offsetUs) throws ExoPlaybackException {
        mStreamFormats = formats;
        mStream = stream;
        mOffsetUs = offsetUs;

        Renderer target = selectRenderer(formats);
        if (target != mActiveRenderer) {
            Log.i(TAG, "replaceStream(): switching active renderer from %s to %s",
                    (mActiveRenderer != null ? mActiveRenderer.getClass().getSimpleName() : "null"),
                    (target != null ? target.getClass().getSimpleName() : "null"));

            if (mActiveRenderer != null) {
                if (mActiveRenderer.getState() == STATE_STARTED) {
                    mActiveRenderer.stop();
                }
                if (mActiveRenderer.getState() == STATE_ENABLED) {
                    mActiveRenderer.disable();
                }
            }

            mActiveRenderer = target;
            if (mActiveRenderer != null) {
                if (mSurface != null) {
                    mActiveRenderer.handleMessage(C.MSG_SET_SURFACE, mSurface);
                }
                if (mFrameMetadataListener != null) {
                    mActiveRenderer.handleMessage(C.MSG_SET_VIDEO_FRAME_METADATA_LISTENER, mFrameMetadataListener);
                }
                mActiveRenderer.enable(mConfiguration, formats, stream, mPositionUs, false, offsetUs);
                if (mOperatingRate != 1f) {
                    mActiveRenderer.setOperatingRate(mOperatingRate);
                }
                if (mState == STATE_STARTED) {
                    mActiveRenderer.start();
                }
            }
        } else if (mActiveRenderer != null) {
            mActiveRenderer.replaceStream(formats, stream, offsetUs);
        }
    }

    @Override
    public SampleStream getStream() {
        return mActiveRenderer != null ? mActiveRenderer.getStream() : mStream;
    }

    @Override
    public boolean hasReadStreamToEnd() {
        return mActiveRenderer != null && mActiveRenderer.hasReadStreamToEnd();
    }

    @Override
    public long getReadingPositionUs() {
        return mActiveRenderer != null ? mActiveRenderer.getReadingPositionUs() : C.TIME_END_OF_SOURCE;
    }

    @Override
    public void setCurrentStreamFinal() {
        if (mActiveRenderer != null) {
            mActiveRenderer.setCurrentStreamFinal();
        }
    }

    @Override
    public boolean isCurrentStreamFinal() {
        return mActiveRenderer != null && mActiveRenderer.isCurrentStreamFinal();
    }

    @Override
    public void maybeThrowStreamError() throws IOException {
        if (mActiveRenderer != null) {
            mActiveRenderer.maybeThrowStreamError();
        }
    }

    @Override
    public void resetPosition(long positionUs) throws ExoPlaybackException {
        mPositionUs = positionUs;
        if (mActiveRenderer != null) {
            mActiveRenderer.resetPosition(positionUs);
        }
    }

    @Override
    public void setOperatingRate(float operatingRate) throws ExoPlaybackException {
        mOperatingRate = operatingRate;
        if (mMediaCodecRenderer != null) {
            mMediaCodecRenderer.setOperatingRate(operatingRate);
        }
        if (mDav1dRenderer != null) {
            mDav1dRenderer.setOperatingRate(operatingRate);
        }
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        mPositionUs = positionUs;
        if (mActiveRenderer != null) {
            mActiveRenderer.render(positionUs, elapsedRealtimeUs);
        }
    }

    @Override
    public boolean isReady() {
        return mActiveRenderer != null ? mActiveRenderer.isReady() : true;
    }

    @Override
    public boolean isEnded() {
        return mActiveRenderer != null ? mActiveRenderer.isEnded() : true;
    }

    @Override
    public void stop() throws ExoPlaybackException {
        mState = STATE_ENABLED;
        if (mActiveRenderer != null) {
            mActiveRenderer.stop();
        }
    }

    @Override
    public void disable() {
        mState = STATE_DISABLED;
        if (mActiveRenderer != null) {
            mActiveRenderer.disable();
            mActiveRenderer = null;
        }
        mStream = null;
        mStreamFormats = null;
    }

    @Override
    public void reset() {
        mState = STATE_DISABLED;
        if (mActiveRenderer != null) {
            mActiveRenderer.reset();
            mActiveRenderer = null;
        }
        if (mMediaCodecRenderer != null) {
            mMediaCodecRenderer.reset();
        }
        if (mDav1dRenderer != null) {
            mDav1dRenderer.reset();
        }
        mStream = null;
        mStreamFormats = null;
    }

    @Override
    public void handleMessage(int messageType, @Nullable Object message) throws ExoPlaybackException {
        if (messageType == C.MSG_SET_SURFACE) {
            mSurface = (Surface) message;
            if (mMediaCodecRenderer != null) {
                mMediaCodecRenderer.handleMessage(messageType, message);
            }
            if (mDav1dRenderer != null) {
                mDav1dRenderer.handleMessage(messageType, message);
            }
        } else if (messageType == C.MSG_SET_VIDEO_FRAME_METADATA_LISTENER) {
            mFrameMetadataListener = (VideoFrameMetadataListener) message;
            if (mMediaCodecRenderer != null) {
                mMediaCodecRenderer.handleMessage(messageType, message);
            }
            if (mDav1dRenderer != null) {
                mDav1dRenderer.handleMessage(messageType, message);
            }
        } else {
            if (mActiveRenderer != null) {
                mActiveRenderer.handleMessage(messageType, message);
            } else if (mMediaCodecRenderer != null) {
                mMediaCodecRenderer.handleMessage(messageType, message);
            }
        }
    }

    // ==========================================
    // RendererCapabilities Implementation
    // ==========================================

    @Override
    public int supportsFormat(Format format) throws ExoPlaybackException {
        if (format == null) {
            return FORMAT_UNSUPPORTED_TYPE;
        }

        if (MimeTypes.VIDEO_AV1.equalsIgnoreCase(format.sampleMimeType)) {
            boolean swForced = mPlayerTweaksData != null && mPlayerTweaksData.isSWDecoderForced();
            if (!swForced && mMediaCodecRenderer != null) {
                try {
                    int mcSupport = mMediaCodecRenderer.getCapabilities().supportsFormat(format);
                    if ((mcSupport & FORMAT_SUPPORT_MASK) == FORMAT_HANDLED) {
                        return mcSupport;
                    }
                } catch (Exception e) {
                    // Fall back to dav1d
                }
            }
            if (mDav1dRenderer != null) {
                return mDav1dRenderer.getCapabilities().supportsFormat(format);
            }
        }

        return mMediaCodecRenderer != null ?
                mMediaCodecRenderer.getCapabilities().supportsFormat(format) : FORMAT_UNSUPPORTED_TYPE;
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
        return ADAPTIVE_NOT_SUPPORTED;
    }

    // ==========================================
    // Helpers
    // ==========================================

    private Renderer selectRenderer(Format[] formats) {
        Format format = (formats != null && formats.length > 0) ? formats[0] : null;
        if (shouldUseDav1d(format)) {
            return mDav1dRenderer;
        }
        return mMediaCodecRenderer;
    }

    private boolean shouldUseDav1d(Format format) {
        if (format == null || format.sampleMimeType == null || mDav1dRenderer == null) {
            return false;
        }
        if (!MimeTypes.VIDEO_AV1.equalsIgnoreCase(format.sampleMimeType)) {
            return false;
        }
        if (!Dav1dLibrary.isAvailable() || !Dav1dLibrary.isEnabled()) {
            return false;
        }
        boolean swForced = mPlayerTweaksData != null && mPlayerTweaksData.isSWDecoderForced();
        if (!swForced && mMediaCodecRenderer != null) {
            try {
                int mcSupport = mMediaCodecRenderer.getCapabilities().supportsFormat(format);
                if ((mcSupport & RendererCapabilities.FORMAT_SUPPORT_MASK) == RendererCapabilities.FORMAT_HANDLED) {
                    return false; // Hardware AV1 decoder available and SW not forced
                }
            } catch (Exception e) {
                // Fall back to dav1d
            }
        }
        return true;
    }
}
