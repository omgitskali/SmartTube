/*
 * Copyright 2021 The Android Open Source Project
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
#include "cpu_info.h"

#include <unistd.h>

#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace dav1d_jni {

int GetNumberOfProcessorsOnline() {
  long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);
  if (num_cpus < 0) {
    return 0;
  }
  return static_cast<int>(num_cpus);
}

#if defined(__arm__) || defined(__aarch64__)

long GetCpuinfoMaxFreq(int cpu_index) {
  char buffer[128];
  const int rv = snprintf(
      buffer, sizeof(buffer),
      "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu_index);
  if (rv < 0 || rv >= sizeof(buffer)) {
    return 0;
  }
  FILE* file = fopen(buffer, "r");
  if (file == nullptr) {
    return 0;
  }
  char* const str = fgets(buffer, sizeof(buffer), file);
  fclose(file);
  if (str == nullptr) {
    return 0;
  }
  const long freq = strtol(str, nullptr, 10);
  if (freq <= 0 || freq == LONG_MAX) {
    return 0;
  }
  return freq;
}

int GetNumberOfPerformanceCoresOnline() {
  FILE* file = fopen("/sys/devices/system/cpu/online", "r");
  if (file == nullptr) {
    return 0;
  }
  char online[512];
  char* const str = fgets(online, sizeof(online), file);
  fclose(file);
  file = nullptr;
  if (str == nullptr) {
    return 0;
  }

  long slowest_cpu_freq = LONG_MAX;
  int num_slowest_cpus = 0;
  int num_cpus = 0;
  const char* cp = online;
  int range_begin = -1;
  while (true) {
    char* str_end;
    const int cpu = static_cast<int>(strtol(cp, &str_end, 10));
    if (str_end == cp) {
      break;
    }
    cp = str_end;
    if (*cp == '-') {
      range_begin = cpu;
    } else {
      if (range_begin == -1) {
        range_begin = cpu;
      }

      num_cpus += cpu - range_begin + 1;
      for (int i = range_begin; i <= cpu; ++i) {
        const long freq = GetCpuinfoMaxFreq(i);
        if (freq <= 0) {
          return 0;
        }
        if (freq < slowest_cpu_freq) {
          slowest_cpu_freq = freq;
          num_slowest_cpus = 0;
        }
        if (freq == slowest_cpu_freq) {
          ++num_slowest_cpus;
        }
      }

      range_begin = -1;
    }
    if (*cp == '\0') {
      break;
    }
    ++cp;
  }

  if (num_slowest_cpus < num_cpus) {
    num_cpus -= num_slowest_cpus;
  }
  return num_cpus;
}

#else

int GetNumberOfPerformanceCoresOnline() {
  return GetNumberOfProcessorsOnline();
}

#endif

}  // namespace dav1d_jni
