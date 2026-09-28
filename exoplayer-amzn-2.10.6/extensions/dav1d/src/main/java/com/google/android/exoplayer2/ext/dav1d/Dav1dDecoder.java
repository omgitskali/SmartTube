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

import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import java.nio.ByteBuffer;

/** Dav1d decoder. */
public final class Dav1dDecoder extends SimpleDecoder<Dav1dInputBuffer, Dav1dOutputBuffer, Dav1dDecoderException> {

  public static final int OUTPUT_MODE_NONE = -1;
  public static final int OUTPUT_MODE_YUV = 0;
  public static final int OUTPUT_MODE_SURFACE_YUV = 1;

  private static final int NO_ERROR = 0;
  private static final int DECODE_ERROR = 1;

  private final long dav1dDecContext;
  private volatile int outputMode;

  /**
   * Creates a Dav1dDecoder.
   *
   * @param numInputBuffers Number of input buffers.
   * @param numOutputBuffers Number of output buffers.
   * @param initialInputBufferSize The initial size of each input buffer, in bytes.
   * @param threads Number of threads dav1d will use to decode.
   * @param maxFrameDelay Maximum frame delay for dav1d.
   * @throws Dav1dDecoderException Thrown if an exception occurs when initializing the decoder.
   */
  public Dav1dDecoder(
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads,
      int maxFrameDelay)
      throws Dav1dDecoderException {
    super(new Dav1dInputBuffer[numInputBuffers], new Dav1dOutputBuffer[numOutputBuffers]);
    if (!Dav1dLibrary.isAvailable()) {
      throw new Dav1dDecoderException("Failed to load decoder native libraries.");
    }
    dav1dDecContext = dav1dInit(threads, maxFrameDelay);
    if (dav1dDecContext == 0) {
      throw new Dav1dDecoderException("Failed to initialize dav1d decoder");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "libdav1d" + (Dav1dLibrary.getVersion() != null ? "-" + Dav1dLibrary.getVersion() : "");
  }

  /**
   * Sets the output mode for frames rendered by the decoder.
   *
   * @param outputMode One of {@link #OUTPUT_MODE_NONE}, {@link #OUTPUT_MODE_YUV}, and {@link #OUTPUT_MODE_SURFACE_YUV}.
   */
  public void setOutputMode(int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  protected Dav1dInputBuffer createInputBuffer() {
    return new Dav1dInputBuffer();
  }

  @Override
  protected Dav1dOutputBuffer createOutputBuffer() {
    return new Dav1dOutputBuffer(this);
  }

  @Override
  protected void releaseOutputBuffer(Dav1dOutputBuffer buffer) {
    if (outputMode == OUTPUT_MODE_SURFACE_YUV && !buffer.isDecodeOnly()) {
      dav1dReleaseFrame(dav1dDecContext, buffer);
    }
    super.releaseOutputBuffer(buffer);
  }

  @Override
  protected Dav1dDecoderException createUnexpectedDecodeException(Throwable error) {
    return new Dav1dDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected Dav1dDecoderException decode(
      Dav1dInputBuffer inputBuffer, Dav1dOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      dav1dFlush(dav1dDecContext);
    }
    ByteBuffer inputData = inputBuffer.data;
    int inputSize = inputData.limit();
    int result = dav1dDecode(dav1dDecContext, inputData, inputSize);
    if (result != NO_ERROR) {
      return new Dav1dDecoderException("Decode error: " + dav1dGetErrorMessage(dav1dDecContext));
    }

    if (!inputBuffer.isDecodeOnly()) {
      outputBuffer.init(inputBuffer.timeUs, outputMode);
      int getFrameResult = dav1dGetFrame(dav1dDecContext, outputBuffer);
      if (getFrameResult == 1) {
        outputBuffer.addFlag(C.BUFFER_FLAG_DECODE_ONLY);
      } else if (getFrameResult == -1) {
        return new Dav1dDecoderException("Buffer initialization failed.");
      }
      outputBuffer.colorInfo = inputBuffer.colorInfo;
    }
    return null;
  }

  /** Renders the outputBuffer to the surface. Used with OUTPUT_MODE_SURFACE_YUV only. */
  public void renderToSurface(Dav1dOutputBuffer outputBuffer, Surface surface)
      throws Dav1dDecoderException {
    int getFrameResult = dav1dRenderFrame(dav1dDecContext, surface, outputBuffer);
    if (getFrameResult == -1) {
      throw new Dav1dDecoderException("Buffer render failed.");
    }
  }

  @Override
  public void release() {
    super.release();
    dav1dClose(dav1dDecContext);
  }

  private native long dav1dInit(int threads, int maxFrameDelay);
  private native long dav1dClose(long context);
  private native int dav1dDecode(long context, ByteBuffer encoded, int length);
  private native int dav1dGetFrame(long context, Dav1dOutputBuffer outputBuffer);
  private native int dav1dRenderFrame(long context, Surface surface, Dav1dOutputBuffer outputBuffer);
  private native int dav1dReleaseFrame(long context, Dav1dOutputBuffer outputBuffer);
  private native String dav1dGetErrorMessage(long context);
  private native void dav1dFlush(long context);
}
