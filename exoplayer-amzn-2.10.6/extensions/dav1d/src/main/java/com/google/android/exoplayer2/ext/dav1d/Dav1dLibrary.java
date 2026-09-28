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

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.util.LibraryLoader;

/** Configures and queries the underlying dav1d native library. */
public final class Dav1dLibrary {

  static {
    ExoPlayerLibraryInfo.registerModule("goog.exo.dav1d");
  }

  private static final LibraryLoader LOADER = new LibraryLoader("dav1dJNI");
  private static boolean enabled = true;
  private static int maxHeight = 1080;

  private Dav1dLibrary() {}

  /**
   * Override the names of the native libraries. If an application wishes to call this method,
   * it must do so before calling any other method defined by this class, and before instantiating a
   * {@link Libdav1dVideoRenderer} instance.
   *
   * @param libraries The names of the native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  public static void setEnabled(boolean isEnabled) {
    enabled = isEnabled;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  public static void setMaxHeight(int maxSupportedHeight) {
    maxHeight = maxSupportedHeight;
  }

  public static int getMaxHeight() {
    return maxHeight;
  }

  public static boolean isResolutionSupported(int height) {
    return enabled && height <= maxHeight;
  }

  /** Returns the version of the underlying library if available, or null otherwise. */
  @Nullable
  public static String getVersion() {
    return isAvailable() ? dav1dGetVersion() : null;
  }

  private static native String dav1dGetVersion();
}
