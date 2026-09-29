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

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>

#include "dav1d/common.h"
#include "dav1d/data.h"
#include "dav1d/dav1d.h"
#include "dav1d/picture.h"
#include "cpu_info.h"

#define LOG_TAG "dav1d_jni"
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))

#define DECODER_FUNC(RETURN_TYPE, NAME, ...) \
  extern "C" { \
  JNIEXPORT RETURN_TYPE \
    Java_com_google_android_exoplayer2_ext_dav1d_Dav1dDecoder_ ## NAME \
      (JNIEnv* env, jobject thiz, ##__VA_ARGS__);\
  } \
  JNIEXPORT RETURN_TYPE \
    Java_com_google_android_exoplayer2_ext_dav1d_Dav1dDecoder_ ## NAME \
      (JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...) \
  extern "C" { \
  JNIEXPORT RETURN_TYPE \
    Java_com_google_android_exoplayer2_ext_dav1d_Dav1dLibrary_ ## NAME \
      (JNIEnv* env, jclass clazz, ##__VA_ARGS__);\
  } \
  JNIEXPORT RETURN_TYPE \
    Java_com_google_android_exoplayer2_ext_dav1d_Dav1dLibrary_ ## NAME \
      (JNIEnv* env, jclass clazz, ##__VA_ARGS__)

namespace {

const int kPlaneY = 0;
const int kPlaneU = 1;
const int kPlaneV = 2;

const int kImageFormatYV12 = 0x32315659; // HAL_PIXEL_FORMAT_YV12
const int kImageFormatP010 = 0x36;       // HAL_PIXEL_FORMAT_YCbCr_P010
const int kP010Shift = 6;
const uint8_t kMidGray = 128;

jfieldID decoderDataField = nullptr;
jmethodID initForPrivateFrameMethod = nullptr;

struct Context {
  Dav1dContext* decoder = nullptr;
  ANativeWindow* native_window = nullptr;
  jobject surface = nullptr;
  int native_window_width = 0;
  int native_window_height = 0;
  int native_window_format = 0;
  char error_message[256] = {0};
  std::mutex mutex;
};

inline int32_t AlignTo16(int32_t value) {
  return (value + 15) & (~15);
}

void CopyPlane(const uint8_t* src, ptrdiff_t src_stride, uint8_t* dest,
               ptrdiff_t dest_stride, int32_t width, int32_t height) {
  for (int y = 0; y < height; ++y) {
    memcpy(dest, src, width);
    src += src_stride;
    dest += dest_stride;
  }
}

void FillPlane(uint8_t value, uint8_t* dest, ptrdiff_t dest_stride,
               int32_t width, int32_t height) {
  for (int y = 0; y < height; ++y) {
    memset(dest, value, width);
    dest += dest_stride;
  }
}

void RenderFrame8Bit(const Dav1dPicture* pic, ANativeWindow_Buffer& buffer,
                     int32_t copy_width, int32_t copy_height) {
  const int32_t y_plane_size = buffer.stride * buffer.height;
  const int32_t uv_height = (buffer.height + 1) / 2;
  const int32_t uv_stride = AlignTo16(buffer.stride / 2);
  const int32_t v_plane_size = uv_height * uv_stride;

  uint8_t* uv_base = reinterpret_cast<uint8_t*>(buffer.bits) + y_plane_size;
  // In YV12 format, V plane precedes U plane
  uint8_t* v_dest = uv_base;
  uint8_t* u_dest = uv_base + v_plane_size;

  const int32_t uv_copy_height = (copy_height + 1) / 2;
  const int32_t uv_copy_width = (copy_width + 1) / 2;

  // Y plane
  CopyPlane(reinterpret_cast<const uint8_t*>(pic->data[kPlaneY]),
            pic->stride[kPlaneY],
            reinterpret_cast<uint8_t*>(buffer.bits),
            buffer.stride, copy_width, copy_height);

  const Dav1dSequenceHeader* header = pic->seq_hdr;
  if (header && header->monochrome) {
    FillPlane(kMidGray, v_dest, uv_stride, uv_copy_width, uv_copy_height);
    FillPlane(kMidGray, u_dest, uv_stride, uv_copy_width, uv_copy_height);
  } else {
    CopyPlane(reinterpret_cast<const uint8_t*>(pic->data[kPlaneV]),
              pic->stride[1], v_dest, uv_stride, uv_copy_width, uv_copy_height);
    CopyPlane(reinterpret_cast<const uint8_t*>(pic->data[kPlaneU]),
              pic->stride[1], u_dest, uv_stride, uv_copy_width, uv_copy_height);
  }
}

void RenderFrame10Bit(const Dav1dPicture* pic, ANativeWindow_Buffer& buffer,
                      int32_t copy_width, int32_t copy_height) {
  const int32_t y_plane_size = buffer.stride * buffer.height;
  const int32_t uv_height = (buffer.height + 1) / 2;
  const int32_t uv_stride = AlignTo16(buffer.stride);

  uint8_t* uv_base = reinterpret_cast<uint8_t*>(buffer.bits) + y_plane_size * 2;

  // Copy Y plane (16-bit words)
  const uint16_t* src_y = reinterpret_cast<const uint16_t*>(pic->data[kPlaneY]);
  uint16_t* dest_y = reinterpret_cast<uint16_t*>(buffer.bits);
  ptrdiff_t src_y_stride = pic->stride[kPlaneY] / 2;
  ptrdiff_t dest_y_stride = buffer.stride;

  for (int y = 0; y < copy_height; ++y) {
    for (int x = 0; x < copy_width; ++x) {
      dest_y[x] = src_y[x] << kP010Shift;
    }
    src_y += src_y_stride;
    dest_y += dest_y_stride;
  }

  // UV interleaved (P010)
  const uint16_t* src_u = reinterpret_cast<const uint16_t*>(pic->data[kPlaneU]);
  const uint16_t* src_v = reinterpret_cast<const uint16_t*>(pic->data[kPlaneV]);
  uint16_t* dest_uv = reinterpret_cast<uint16_t*>(uv_base);
  ptrdiff_t src_uv_stride = pic->stride[kPlaneU] / 2;
  ptrdiff_t dest_uv_stride = uv_stride / 2;

  const int32_t uv_copy_height = (copy_height + 1) / 2;
  const int32_t uv_copy_width = (copy_width + 1) / 2;

  for (int y = 0; y < uv_copy_height; ++y) {
    for (int x = 0; x < uv_copy_width; ++x) {
      dest_uv[2 * x] = src_u[x] << kP010Shift;
      dest_uv[2 * x + 1] = src_v[x] << kP010Shift;
    }
    src_u += src_uv_stride;
    src_v += src_uv_stride;
    dest_uv += dest_uv_stride;
  }
}

void RenderFrame10BitTo8Bit(const Dav1dPicture* pic, ANativeWindow_Buffer& buffer,
                            int32_t copy_width, int32_t copy_height) {
  const int32_t y_plane_size = buffer.stride * buffer.height;
  const int32_t uv_height = (buffer.height + 1) / 2;
  const int32_t uv_stride = AlignTo16(buffer.stride / 2);
  const int32_t v_plane_size = uv_height * uv_stride;

  uint8_t* uv_base = reinterpret_cast<uint8_t*>(buffer.bits) + y_plane_size;
  uint8_t* v_dest = uv_base;
  uint8_t* u_dest = uv_base + v_plane_size;

  const int32_t uv_copy_height = (copy_height + 1) / 2;
  const int32_t uv_copy_width = (copy_width + 1) / 2;

  // Y plane: 16-bit to 8-bit (shift right by 2)
  const uint16_t* src_y = reinterpret_cast<const uint16_t*>(pic->data[kPlaneY]);
  uint8_t* dest_y = reinterpret_cast<uint8_t*>(buffer.bits);
  ptrdiff_t src_y_stride = pic->stride[kPlaneY] / 2;
  for (int y = 0; y < copy_height; ++y) {
    for (int x = 0; x < copy_width; ++x) {
      dest_y[x] = static_cast<uint8_t>(src_y[x] >> 2);
    }
    src_y += src_y_stride;
    dest_y += buffer.stride;
  }

  // UV planes: 16-bit to 8-bit
  const uint16_t* src_u = reinterpret_cast<const uint16_t*>(pic->data[kPlaneU]);
  const uint16_t* src_v = reinterpret_cast<const uint16_t*>(pic->data[kPlaneV]);
  ptrdiff_t src_uv_stride = pic->stride[1] / 2;
  for (int y = 0; y < uv_copy_height; ++y) {
    for (int x = 0; x < uv_copy_width; ++x) {
      v_dest[x] = static_cast<uint8_t>(src_v[x] >> 2);
      u_dest[x] = static_cast<uint8_t>(src_u[x] >> 2);
    }
    src_u += src_uv_stride;
    src_v += src_uv_stride;
    v_dest += uv_stride;
    u_dest += uv_stride;
  }
}

} // namespace

DECODER_FUNC(jlong, dav1dInit, jint threads, jint max_frame_delay) {
  if (decoderDataField == nullptr) {
    jclass outputBufferClass = env->FindClass("com/google/android/exoplayer2/ext/dav1d/Dav1dOutputBuffer");
    if (outputBufferClass != nullptr) {
      decoderDataField = env->GetFieldID(outputBufferClass, "decoderData", "J");
      initForPrivateFrameMethod = env->GetMethodID(outputBufferClass, "initForPrivateFrame", "(II)V");
      env->DeleteLocalRef(outputBufferClass);
    }
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      LOGE("dav1dInit: Failed to find Dav1dOutputBuffer fields/methods");
    }
  }

  Context* ctx = new (std::nothrow) Context();
  if (!ctx) {
    return 0;
  }

  Dav1dSettings settings;
  dav1d_default_settings(&settings);

  if (threads <= 0) {
    threads = dav1d_jni::GetNumberOfPerformanceCoresOnline();
    if (threads <= 0) {
      threads = dav1d_jni::GetNumberOfProcessorsOnline();
    }
  }
  settings.n_threads = std::max(1, (int)threads);
  settings.max_frame_delay = max_frame_delay > 0 ? max_frame_delay : 1;

  int result = dav1d_open(&ctx->decoder, &settings);
  if (result < 0) {
    snprintf(ctx->error_message, sizeof(ctx->error_message), "dav1d_open failed: %d", result);
    LOGE("%s", ctx->error_message);
    delete ctx;
    return 0;
  }

  return reinterpret_cast<jlong>(ctx);
}

DECODER_FUNC(jlong, dav1dClose, jlong jContext) {
  if (jContext == 0) return 0;
  Context* ctx = reinterpret_cast<Context*>(jContext);
  std::lock_guard<std::mutex> lock(ctx->mutex);
  if (ctx->decoder) {
    dav1d_close(&ctx->decoder);
    ctx->decoder = nullptr;
  }
  if (ctx->native_window) {
    ANativeWindow_release(ctx->native_window);
    ctx->native_window = nullptr;
  }
  if (ctx->surface) {
    env->DeleteGlobalRef(ctx->surface);
    ctx->surface = nullptr;
  }
  delete ctx;
  return 0;
}

DECODER_FUNC(jint, dav1dDecode, jlong jContext, jobject encodedBuffer, jint length) {
  if (jContext == 0 || length <= 0) return 0;
  Context* ctx = reinterpret_cast<Context*>(jContext);
  std::lock_guard<std::mutex> lock(ctx->mutex);

  uint8_t* buffer = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(encodedBuffer));
  if (!buffer) {
    snprintf(ctx->error_message, sizeof(ctx->error_message), "GetDirectBufferAddress failed");
    LOGE("%s", ctx->error_message);
    return 1;
  }

  Dav1dData data;
  uint8_t* dst = dav1d_data_create(&data, length);
  if (!dst) {
    snprintf(ctx->error_message, sizeof(ctx->error_message), "dav1d_data_create failed for size %d", length);
    LOGE("%s", ctx->error_message);
    return 1;
  }
  memcpy(dst, buffer, length);

  int res = dav1d_send_data(ctx->decoder, &data);
  if (res < 0) {
    dav1d_data_unref(&data);
    if (res != DAV1D_ERR(EAGAIN)) {
      snprintf(ctx->error_message, sizeof(ctx->error_message), "dav1d_send_data error: %d (%s)", res, strerror(DAV1D_ERR(res)));
      LOGE("%s", ctx->error_message);
      return 1;
    }
  }

  return 0;
}

DECODER_FUNC(jint, dav1dGetFrame, jlong jContext, jobject jOutputBuffer) {
  if (jContext == 0) return -1;
  Context* ctx = reinterpret_cast<Context*>(jContext);
  std::lock_guard<std::mutex> lock(ctx->mutex);

  Dav1dPicture* pic = new (std::nothrow) Dav1dPicture();
  if (!pic) return -1;
  memset(pic, 0, sizeof(Dav1dPicture));

  int res = dav1d_get_picture(ctx->decoder, pic);
  if (res < 0) {
    delete pic;
    if (res == DAV1D_ERR(EAGAIN)) {
      return 1; // Decode only or need more data
    }
    snprintf(ctx->error_message, sizeof(ctx->error_message), "dav1d_get_picture error: %d (%s)", res, strerror(DAV1D_ERR(res)));
    LOGE("%s", ctx->error_message);
    return -1;
  }

  if (initForPrivateFrameMethod) {
    env->CallVoidMethod(jOutputBuffer, initForPrivateFrameMethod, pic->p.w, pic->p.h);
  }
  if (decoderDataField) {
    env->SetLongField(jOutputBuffer, decoderDataField, reinterpret_cast<jlong>(pic));
  }

  return 0;
}

DECODER_FUNC(jint, dav1dRenderFrame, jlong jContext, jobject surface, jobject jOutputBuffer) {
  if (jContext == 0 || surface == nullptr || jOutputBuffer == nullptr) return -1;
  Context* ctx = reinterpret_cast<Context*>(jContext);
  std::lock_guard<std::mutex> lock(ctx->mutex);

  Dav1dPicture* pic = nullptr;
  if (decoderDataField) {
    pic = reinterpret_cast<Dav1dPicture*>(env->GetLongField(jOutputBuffer, decoderDataField));
  }
  if (!pic) return -1;

  if (ctx->surface == nullptr || !env->IsSameObject(ctx->surface, surface)) {
    if (ctx->native_window) {
      ANativeWindow_release(ctx->native_window);
      ctx->native_window = nullptr;
    }
    if (ctx->surface) {
      env->DeleteGlobalRef(ctx->surface);
      ctx->surface = nullptr;
    }
    ctx->surface = env->NewGlobalRef(surface);
    ctx->native_window = ANativeWindow_fromSurface(env, surface);
    ctx->native_window_width = 0;
    ctx->native_window_height = 0;
    ctx->native_window_format = 0;
  }

  if (!ctx->native_window) {
    snprintf(ctx->error_message, sizeof(ctx->error_message), "ANativeWindow_fromSurface failed");
    LOGE("%s", ctx->error_message);
    return -1;
  }

  int buffer_format = (pic->p.bpc == 10) ? kImageFormatP010 : kImageFormatYV12;
  bool format_fallback_8bit = false;

  if (ctx->native_window_width != pic->p.w || ctx->native_window_height != pic->p.h ||
      ctx->native_window_format != buffer_format) {
    int geom_res = ANativeWindow_setBuffersGeometry(ctx->native_window, pic->p.w, pic->p.h, buffer_format);
    if (geom_res != 0 && buffer_format == kImageFormatP010) {
      LOGW("P010 geometry unsupported (res=%d), falling back to YV12", geom_res);
      buffer_format = kImageFormatYV12;
      format_fallback_8bit = true;
      geom_res = ANativeWindow_setBuffersGeometry(ctx->native_window, pic->p.w, pic->p.h, buffer_format);
    }
    if (geom_res != 0) {
      snprintf(ctx->error_message, sizeof(ctx->error_message),
               "ANativeWindow_setBuffersGeometry failed: res=%d, w=%d, h=%d, fmt=0x%x",
               geom_res, pic->p.w, pic->p.h, buffer_format);
      LOGE("%s", ctx->error_message);
      return -1;
    }
    ctx->native_window_width = pic->p.w;
    ctx->native_window_height = pic->p.h;
    ctx->native_window_format = buffer_format;
  } else if (ctx->native_window_format == kImageFormatYV12 && pic->p.bpc == 10) {
    format_fallback_8bit = true;
  }

  ANativeWindow_Buffer buffer;
  int lock_res = ANativeWindow_lock(ctx->native_window, &buffer, nullptr);
  if (lock_res != 0 || buffer.bits == nullptr) {
    LOGW("ANativeWindow_lock failed (res=%d, bits=%p), attempting window recovery...", lock_res, buffer.bits);
    if (ctx->native_window) {
      ANativeWindow_release(ctx->native_window);
      ctx->native_window = nullptr;
    }
    ctx->native_window = ANativeWindow_fromSurface(env, surface);
    if (ctx->native_window) {
      ANativeWindow_setBuffersGeometry(ctx->native_window, pic->p.w, pic->p.h, buffer_format);
      ctx->native_window_width = pic->p.w;
      ctx->native_window_height = pic->p.h;
      ctx->native_window_format = buffer_format;
      lock_res = ANativeWindow_lock(ctx->native_window, &buffer, nullptr);
    }
  }

  if (lock_res != 0 || buffer.bits == nullptr) {
    snprintf(ctx->error_message, sizeof(ctx->error_message),
             "ANativeWindow_lock failed (res=%d, bits=%p, w=%d, h=%d)",
             lock_res, buffer.bits, pic->p.w, pic->p.h);
    LOGE("%s", ctx->error_message);
    return -1;
  }

  int32_t copy_height = std::min((int32_t)pic->p.h, (int32_t)buffer.height);
  int32_t copy_width = std::min((int32_t)pic->p.w, (int32_t)buffer.stride);

  if (pic->p.bpc == 10 && !format_fallback_8bit) {
    RenderFrame10Bit(pic, buffer, copy_width, copy_height);
  } else if (pic->p.bpc == 10 && format_fallback_8bit) {
    RenderFrame10BitTo8Bit(pic, buffer, copy_width, copy_height);
  } else {
    RenderFrame8Bit(pic, buffer, copy_width, copy_height);
  }

  int unlock_res = ANativeWindow_unlockAndPost(ctx->native_window);
  if (unlock_res != 0) {
    snprintf(ctx->error_message, sizeof(ctx->error_message),
             "ANativeWindow_unlockAndPost failed: %d", unlock_res);
    LOGE("%s", ctx->error_message);
    return -1;
  }

  return 0;
}

DECODER_FUNC(void, dav1dReleaseFrame, jlong jContext, jobject jOutputBuffer) {
  if (jOutputBuffer == nullptr) return;
  Dav1dPicture* pic = nullptr;
  if (decoderDataField) {
    pic = reinterpret_cast<Dav1dPicture*>(env->GetLongField(jOutputBuffer, decoderDataField));
    env->SetLongField(jOutputBuffer, decoderDataField, 0);
  }
  if (pic) {
    dav1d_picture_unref(pic);
    delete pic;
  }
}

DECODER_FUNC(void, dav1dFlush, jlong jContext) {
  if (jContext == 0) return;
  Context* ctx = reinterpret_cast<Context*>(jContext);
  std::lock_guard<std::mutex> lock(ctx->mutex);
  if (ctx->decoder) {
    dav1d_flush(ctx->decoder);
  }
}

DECODER_FUNC(jstring, dav1dGetErrorMessage, jlong jContext) {
  if (jContext == 0) {
    return env->NewStringUTF("Invalid context");
  }
  Context* ctx = reinterpret_cast<Context*>(jContext);
  return env->NewStringUTF(ctx->error_message);
}

LIBRARY_FUNC(jstring, dav1dGetVersion) {
  return env->NewStringUTF(dav1d_version());
}
