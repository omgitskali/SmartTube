/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ext.dav1d;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.ExoMediaCrypto;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.TimedValueQueue;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoFrameMetadataListener;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.video.VideoRendererEventListener.EventDispatcher;

/**
 * Decodes and renders video using the dav1d decoder.
 */
public class Libdav1dVideoRenderer extends BaseRenderer {

  private static final String TAG = "Libdav1dVideoRenderer";

  private static final int REINITIALIZATION_STATE_NONE = 0;
  private static final int REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM = 1;
  private static final int REINITIALIZATION_STATE_WAIT_END_OF_STREAM = 2;

  private static final int DEFAULT_INPUT_BUFFER_SIZE = 768 * 1024;
  private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 4;
  private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 4;
  private static final int DEFAULT_MAX_FRAME_DELAY = 2;

  private final int numInputBuffers;
  private final int numOutputBuffers;
  private final int threads;
  private final int maxFrameDelay;
  private final long allowedJoiningTimeMs;
  private final int maxDroppedFramesToNotify;
  private final boolean playClearSamplesWithoutKeys;
  private final EventDispatcher eventDispatcher;
  private final FormatHolder formatHolder;
  private final TimedValueQueue<Format> formatQueue;
  private final DecoderInputBuffer flagsOnlyBuffer;
  private final DrmSessionManager<ExoMediaCrypto> drmSessionManager;

  private Format format;
  private Format pendingFormat;
  private Format outputFormat;
  private int reportedWidth = Format.NO_VALUE;
  private int reportedHeight = Format.NO_VALUE;
  private Dav1dDecoder decoder;
  private Dav1dInputBuffer inputBuffer;
  private Dav1dOutputBuffer outputBuffer;
  private Dav1dOutputBuffer nextOutputBuffer;

  private DrmSession<ExoMediaCrypto> decoderDrmSession;
  private DrmSession<ExoMediaCrypto> sourceDrmSession;

  private int decoderReinitializationState;
  private boolean decoderReceivedBuffers;

  private boolean inputStreamEnded;
  private boolean outputStreamEnded;
  private boolean renderedFirstFrame;
  private long initialPositionUs;
  private long joiningDeadlineMs;
  private boolean waitingForKeys;

  private Surface surface;
  private int outputMode;

  private int droppedFrames;
  private int consecutiveDroppedFrameCount;
  private int buffersInCodecCount;
  private long lastRenderTimeUs;
  private long outputStreamOffsetUs;
  private VideoFrameMetadataListener frameMetadataListener;

  protected DecoderCounters decoderCounters;

  public Libdav1dVideoRenderer(long allowedJoiningTimeMs) {
    this(allowedJoiningTimeMs, null, null, 0);
  }

  public Libdav1dVideoRenderer(
      long allowedJoiningTimeMs,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        null,
        false);
  }

  public Libdav1dVideoRenderer(
      long allowedJoiningTimeMs,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      DrmSessionManager<ExoMediaCrypto> drmSessionManager,
      boolean playClearSamplesWithoutKeys) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        drmSessionManager,
        playClearSamplesWithoutKeys,
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        DEFAULT_MAX_FRAME_DELAY,
        DEFAULT_NUM_OF_INPUT_BUFFERS,
        DEFAULT_NUM_OF_OUTPUT_BUFFERS);
  }

  public Libdav1dVideoRenderer(
      long allowedJoiningTimeMs,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      DrmSessionManager<ExoMediaCrypto> drmSessionManager,
      boolean playClearSamplesWithoutKeys,
      int threads,
      int maxFrameDelay,
      int numInputBuffers,
      int numOutputBuffers) {
    super(C.TRACK_TYPE_VIDEO);
    this.allowedJoiningTimeMs = allowedJoiningTimeMs;
    this.maxDroppedFramesToNotify = maxDroppedFramesToNotify;
    this.drmSessionManager = drmSessionManager;
    this.playClearSamplesWithoutKeys = playClearSamplesWithoutKeys;
    this.threads = threads;
    this.maxFrameDelay = maxFrameDelay;
    this.numInputBuffers = numInputBuffers;
    this.numOutputBuffers = numOutputBuffers;
    joiningDeadlineMs = C.TIME_UNSET;
    clearReportedVideoSize();
    formatHolder = new FormatHolder();
    formatQueue = new TimedValueQueue<>();
    flagsOnlyBuffer = DecoderInputBuffer.newFlagsOnlyInstance();
    eventDispatcher = new EventDispatcher(eventHandler, eventListener);
    decoderReinitializationState = REINITIALIZATION_STATE_NONE;
    outputMode = Dav1dDecoder.OUTPUT_MODE_NONE;
  }

  @Override
  public int supportsFormat(Format format) {
    if (!Dav1dLibrary.isAvailable() || !Dav1dLibrary.isEnabled() || !MimeTypes.VIDEO_AV1.equalsIgnoreCase(format.sampleMimeType)) {
      return FORMAT_UNSUPPORTED_TYPE;
    } else if (Dav1dLibrary.getMaxHeight() > 0 && format.height > Dav1dLibrary.getMaxHeight()) {
      return FORMAT_UNSUPPORTED_SUBTYPE;
    } else if (!supportsFormatDrm(drmSessionManager, format.drmInitData)) {
      return FORMAT_UNSUPPORTED_DRM;
    }
    return FORMAT_HANDLED | ADAPTIVE_SEAMLESS;
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (outputStreamEnded) {
      return;
    }

    if (format == null) {
      flagsOnlyBuffer.clear();
      int result = readSource(formatHolder, flagsOnlyBuffer, true);
      if (result == C.RESULT_FORMAT_READ) {
        onInputFormatChanged(formatHolder.format);
      } else if (result == C.RESULT_BUFFER_READ) {
        Assertions.checkState(flagsOnlyBuffer.isEndOfStream());
        inputStreamEnded = true;
        outputStreamEnded = true;
        return;
      } else {
        return;
      }
    }

    maybeInitDecoder();

    if (decoder != null) {
      try {
        TraceUtil.beginSection("drainAndFeedDav1d");
        while (drainOutputBuffer(positionUs, elapsedRealtimeUs)) {}
        while (feedInputBuffer()) {}
        TraceUtil.endSection();
      } catch (Dav1dDecoderException e) {
        throw ExoPlaybackException.createForRenderer(e, getIndex());
      }
      decoderCounters.ensureUpdated();
    }
  }

  @Override
  public boolean isEnded() {
    return outputStreamEnded;
  }

  @Override
  public boolean isReady() {
    if (waitingForKeys) {
      return false;
    }
    if (format != null && (isSourceReady() || outputBuffer != null)
        && (renderedFirstFrame || outputMode == Dav1dDecoder.OUTPUT_MODE_NONE)) {
      joiningDeadlineMs = C.TIME_UNSET;
      return true;
    } else if (joiningDeadlineMs == C.TIME_UNSET) {
      return false;
    } else if (SystemClock.elapsedRealtime() < joiningDeadlineMs) {
      return true;
    } else {
      joiningDeadlineMs = C.TIME_UNSET;
      return false;
    }
  }

  @Override
  protected void onEnabled(boolean joining) throws ExoPlaybackException {
    decoderCounters = new DecoderCounters();
    eventDispatcher.enabled(decoderCounters);
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
    inputStreamEnded = false;
    outputStreamEnded = false;
    clearRenderedFirstFrame();
    initialPositionUs = C.TIME_UNSET;
    consecutiveDroppedFrameCount = 0;
    if (decoder != null) {
      flushDecoder();
    }
    if (joining) {
      setJoiningDeadlineMs();
    } else {
      joiningDeadlineMs = C.TIME_UNSET;
    }
    formatQueue.clear();
  }

  @Override
  protected void onStarted() {
    droppedFrames = 0;
    consecutiveDroppedFrameCount = 0;
  }

  @Override
  protected void onStopped() {
    joiningDeadlineMs = C.TIME_UNSET;
    maybeNotifyDroppedFrames();
  }

  @Override
  protected void onDisabled() {
    format = null;
    waitingForKeys = false;
    clearReportedVideoSize();
    clearRenderedFirstFrame();
    try {
      setSourceDrmSession(null);
      releaseDecoder();
    } finally {
      eventDispatcher.disabled(decoderCounters);
    }
  }

  @Override
  protected void onStreamChanged(Format[] formats, long offsetUs) throws ExoPlaybackException {
    outputStreamOffsetUs = offsetUs;
    super.onStreamChanged(formats, offsetUs);
  }

  @Override
  public void handleMessage(int messageType, @Nullable Object message) throws ExoPlaybackException {
    if (messageType == C.MSG_SET_SURFACE) {
      setOutputSurface((Surface) message);
    } else if (messageType == C.MSG_SET_VIDEO_FRAME_METADATA_LISTENER) {
      frameMetadataListener = (VideoFrameMetadataListener) message;
    } else {
      super.handleMessage(messageType, message);
    }
  }

  private void setOutputSurface(@Nullable Surface surface) {
    if (this.surface != surface) {
      this.surface = surface;
      outputMode = surface != null ? Dav1dDecoder.OUTPUT_MODE_SURFACE_YUV : Dav1dDecoder.OUTPUT_MODE_NONE;
      if (outputMode != Dav1dDecoder.OUTPUT_MODE_NONE) {
        if (decoder != null) {
          decoder.setOutputMode(outputMode);
        }
        clearRenderedFirstFrame();
        if (getState() == STATE_STARTED) {
          setJoiningDeadlineMs();
        }
      } else {
        clearReportedVideoSize();
        clearRenderedFirstFrame();
      }
    }
  }

  private boolean feedInputBuffer() throws Dav1dDecoderException, ExoPlaybackException {
    if (decoder == null || decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM
        || inputStreamEnded) {
      return false;
    }

    if (inputBuffer == null) {
      inputBuffer = decoder.dequeueInputBuffer();
      if (inputBuffer == null) {
        return false;
      }
    }

    if (decoderReinitializationState == REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM) {
      inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
      decoder.queueInputBuffer(inputBuffer);
      inputBuffer = null;
      decoderReinitializationState = REINITIALIZATION_STATE_WAIT_END_OF_STREAM;
      return false;
    }

    int result;
    if (waitingForKeys) {
      result = C.RESULT_NOTHING_READ;
    } else {
      result = readSource(formatHolder, inputBuffer, false);
    }

    if (result == C.RESULT_NOTHING_READ) {
      return false;
    }
    if (result == C.RESULT_FORMAT_READ) {
      onInputFormatChanged(formatHolder.format);
      return true;
    }
    if (inputBuffer.isEndOfStream()) {
      inputStreamEnded = true;
      decoder.queueInputBuffer(inputBuffer);
      inputBuffer = null;
      return false;
    }
    boolean bufferEncrypted = inputBuffer.isEncrypted();
    waitingForKeys = shouldWaitForKeys(bufferEncrypted);
    if (waitingForKeys) {
      return false;
    }
    if (pendingFormat != null) {
      formatQueue.add(inputBuffer.timeUs, pendingFormat);
      pendingFormat = null;
    }
    inputBuffer.flip();
    inputBuffer.colorInfo = format.colorInfo;
    decoder.queueInputBuffer(inputBuffer);
    buffersInCodecCount++;
    decoderReceivedBuffers = true;
    decoderCounters.inputBufferCount++;
    inputBuffer = null;
    return true;
  }

  private boolean drainOutputBuffer(long positionUs, long elapsedRealtimeUs)
      throws ExoPlaybackException, Dav1dDecoderException {
    if (outputBuffer == null) {
      outputBuffer = decoder.dequeueOutputBuffer();
      if (outputBuffer == null) {
        return false;
      }
      decoderCounters.skippedOutputBufferCount += outputBuffer.skippedOutputBufferCount;
      buffersInCodecCount -= outputBuffer.skippedOutputBufferCount;
    }

    if (outputBuffer.isEndOfStream()) {
      if (decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM) {
        releaseDecoder();
        maybeInitDecoder();
      } else {
        outputBuffer.release();
        outputBuffer = null;
        outputStreamEnded = true;
      }
      return false;
    }

    boolean processedOutputBuffer = processOutputBuffer(positionUs, elapsedRealtimeUs);
    if (processedOutputBuffer) {
      onProcessedOutputBuffer(outputBuffer.timeUs);
      outputBuffer = null;
    }
    return processedOutputBuffer;
  }

  private boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs)
      throws ExoPlaybackException, Dav1dDecoderException {
    if (initialPositionUs == C.TIME_UNSET) {
      initialPositionUs = positionUs;
    }

    long earlyUs = outputBuffer.timeUs - positionUs;
    if (outputMode == Dav1dDecoder.OUTPUT_MODE_NONE) {
      if (isBufferLate(earlyUs)) {
        skipOutputBuffer(outputBuffer);
        return true;
      }
      return false;
    }

    long presentationTimeUs = outputBuffer.timeUs - outputStreamOffsetUs;
    Format queuedFormat = formatQueue.pollFloor(presentationTimeUs);
    if (queuedFormat != null) {
      outputFormat = queuedFormat;
    }

    if (getState() == STATE_STARTED) {
      if (isBufferVeryLate(earlyUs)) {
        dropOutputBuffer(outputBuffer);
        return true;
      }
    }

    renderOutputBuffer(outputBuffer);
    return true;
  }

  private void renderOutputBuffer(Dav1dOutputBuffer buffer) throws Dav1dDecoderException {
    if (surface != null && buffer.mode == Dav1dDecoder.OUTPUT_MODE_SURFACE_YUV) {
      decoder.renderToSurface(buffer, surface);
      buffer.release();
      consecutiveDroppedFrameCount = 0;
      decoderCounters.renderedOutputBufferCount++;
      maybeNotifyVideoSizeChanged(buffer.width, buffer.height);
      maybeNotifyRenderedFirstFrame();
    } else {
      dropOutputBuffer(buffer);
    }
  }

  private void dropOutputBuffer(Dav1dOutputBuffer buffer) {
    updateDroppedBufferCounters(1);
    buffer.release();
  }

  private void skipOutputBuffer(Dav1dOutputBuffer buffer) {
    decoderCounters.skippedOutputBufferCount++;
    buffer.release();
  }

  private void updateDroppedBufferCounters(int count) {
    decoderCounters.droppedBufferCount += count;
    droppedFrames += count;
    consecutiveDroppedFrameCount += count;
    decoderCounters.maxConsecutiveDroppedBufferCount =
        Math.max(consecutiveDroppedFrameCount, decoderCounters.maxConsecutiveDroppedBufferCount);
    if (maxDroppedFramesToNotify > 0 && droppedFrames >= maxDroppedFramesToNotify) {
      maybeNotifyDroppedFrames();
    }
  }

  private boolean isBufferLate(long earlyUs) {
    return earlyUs < -30000;
  }

  private boolean isBufferVeryLate(long earlyUs) {
    return earlyUs < -500000;
  }

  private void maybeInitDecoder() throws ExoPlaybackException {
    if (decoder != null) {
      return;
    }
    setDecoderDrmSession(sourceDrmSession);
    try {
      int initialInputBufferSize =
          format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
      decoder = new Dav1dDecoder(numInputBuffers, numOutputBuffers, initialInputBufferSize, threads, maxFrameDelay);
      decoder.setOutputMode(outputMode);
    } catch (Dav1dDecoderException e) {
      throw ExoPlaybackException.createForRenderer(e, getIndex());
    }
    decoderCounters.decoderInitCount++;
    eventDispatcher.decoderInitialized(decoder.getName(), SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime());
  }

  private void releaseDecoder() {
    inputBuffer = null;
    outputBuffer = null;
    decoderReinitializationState = REINITIALIZATION_STATE_NONE;
    decoderReceivedBuffers = false;
    buffersInCodecCount = 0;
    if (decoder != null) {
      decoderCounters.decoderReleaseCount++;
      decoder.release();
      decoder = null;
    }
    setDecoderDrmSession(null);
  }

  private void flushDecoder() throws ExoPlaybackException {
    waitingForKeys = false;
    buffersInCodecCount = 0;
    if (decoderReinitializationState != REINITIALIZATION_STATE_NONE) {
      releaseDecoder();
      maybeInitDecoder();
    } else {
      inputBuffer = null;
      if (outputBuffer != null) {
        outputBuffer.release();
        outputBuffer = null;
      }
      decoder.flush();
      decoderReceivedBuffers = false;
    }
  }

  protected void onInputFormatChanged(Format newFormat) throws ExoPlaybackException {
    Format oldFormat = format;
    format = newFormat;
    pendingFormat = newFormat;

    boolean drmInitDataChanged =
        !Util.areEqual(format.drmInitData, oldFormat == null ? null : oldFormat.drmInitData);
    if (drmInitDataChanged) {
      if (format.drmInitData != null) {
        if (drmSessionManager == null) {
          throw ExoPlaybackException.createForRenderer(
              new IllegalStateException("Media requires a DrmSessionManager"), getIndex());
        }
        DrmSession<ExoMediaCrypto> session =
            drmSessionManager.acquireSession(Looper.myLooper(), newFormat.drmInitData);
        if (session == decoderDrmSession || session == sourceDrmSession) {
          drmSessionManager.releaseSession(session);
        }
        setSourceDrmSession(session);
      } else {
        setSourceDrmSession(null);
      }
    }

    if (sourceDrmSession != decoderDrmSession) {
      if (decoderReceivedBuffers) {
        decoderReinitializationState = REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM;
      } else {
        releaseDecoder();
        maybeInitDecoder();
      }
    }

    eventDispatcher.inputFormatChanged(format);
  }

  private void setJoiningDeadlineMs() {
    joiningDeadlineMs = allowedJoiningTimeMs > 0
        ? (SystemClock.elapsedRealtime() + allowedJoiningTimeMs)
        : C.TIME_UNSET;
  }

  private void clearRenderedFirstFrame() {
    renderedFirstFrame = false;
  }

  private void maybeNotifyRenderedFirstFrame() {
    if (!renderedFirstFrame) {
      renderedFirstFrame = true;
      if (surface != null) {
        eventDispatcher.renderedFirstFrame(surface);
      }
    }
  }

  private void maybeNotifyDroppedFrames() {
    if (droppedFrames > 0) {
      long now = SystemClock.elapsedRealtime();
      eventDispatcher.droppedFrames(droppedFrames, now);
      droppedFrames = 0;
    }
  }

  private void clearReportedVideoSize() {
    reportedWidth = Format.NO_VALUE;
    reportedHeight = Format.NO_VALUE;
  }

  private void maybeNotifyVideoSizeChanged(int width, int height) {
    if (reportedWidth != width || reportedHeight != height) {
      reportedWidth = width;
      reportedHeight = height;
      eventDispatcher.videoSizeChanged(width, height, 0, 1);
    }
  }

  private boolean shouldWaitForKeys(boolean bufferEncrypted) throws ExoPlaybackException {
    if (decoderDrmSession == null || (!bufferEncrypted && playClearSamplesWithoutKeys)) {
      return false;
    }
    @DrmSession.State int drmSessionState = decoderDrmSession.getState();
    if (drmSessionState == DrmSession.STATE_ERROR) {
      throw ExoPlaybackException.createForRenderer(decoderDrmSession.getError(), getIndex());
    }
    return drmSessionState != DrmSession.STATE_OPENED_WITH_KEYS;
  }

  private void setSourceDrmSession(@Nullable DrmSession<ExoMediaCrypto> session) {
    DrmSession<ExoMediaCrypto> previous = sourceDrmSession;
    sourceDrmSession = session;
    releaseDrmSessionIfUnused(previous);
  }

  private void setDecoderDrmSession(@Nullable DrmSession<ExoMediaCrypto> session) {
    DrmSession<ExoMediaCrypto> previous = decoderDrmSession;
    decoderDrmSession = session;
    releaseDrmSessionIfUnused(previous);
  }

  private void releaseDrmSessionIfUnused(@Nullable DrmSession<ExoMediaCrypto> session) {
    if (session != null && session != decoderDrmSession && session != sourceDrmSession) {
      if (drmSessionManager != null) {
        drmSessionManager.releaseSession(session);
      }
    }
  }

  protected void onProcessedOutputBuffer(long presentationTimeUs) {
    buffersInCodecCount--;
  }
}
