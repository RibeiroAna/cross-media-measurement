/*
 * Copyright 2023 The Cross-Media Measurement Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wfanet.measurement.reporting.service.api.v2alpha

import org.wfanet.measurement.config.reporting.MetricSpecConfig
import org.wfanet.measurement.reporting.v2alpha.MetricSpec
import org.wfanet.measurement.reporting.v2alpha.MetricSpecKt
import org.wfanet.measurement.reporting.v2alpha.copy

class MetricSpecDefaultsException(message: String? = null, cause: Throwable? = null) :
  Exception(message, cause)

/**
 * Specifies default values using [MetricSpecConfig] when optional fields in the [MetricSpec] are
 * not set.
 */
fun MetricSpec.withDefaults(metricSpecConfig: MetricSpecConfig): MetricSpec {
  return copy {
    val defaultVidSamplingInterval: MetricSpecConfig.VidSamplingInterval =
      when (typeCase) {
        MetricSpec.TypeCase.REACH -> {
          reach = reach.withDefaults(metricSpecConfig)
          metricSpecConfig.reachVidSamplingInterval
        }
        MetricSpec.TypeCase.FREQUENCY_HISTOGRAM -> {
          frequencyHistogram = frequencyHistogram.withDefaults(metricSpecConfig)
          metricSpecConfig.frequencyHistogramVidSamplingInterval
        }
        MetricSpec.TypeCase.IMPRESSION_COUNT -> {
          impressionCount = impressionCount.withDefaults(metricSpecConfig)
          metricSpecConfig.impressionCountVidSamplingInterval
        }
        MetricSpec.TypeCase.WATCH_DURATION -> {
          watchDuration = watchDuration.withDefaults(metricSpecConfig)
          metricSpecConfig.watchDurationVidSamplingInterval
        }
        MetricSpec.TypeCase.TYPE_NOT_SET ->
          throw MetricSpecDefaultsException(
            "Invalid metric spec type",
            IllegalArgumentException("The metric type in Metric is not specified.")
          )
      }

    vidSamplingInterval =
      if (hasVidSamplingInterval()) {
        vidSamplingInterval
      } else defaultVidSamplingInterval.toVidSamplingInterval()

    if (vidSamplingInterval.start < 0) {
      throw MetricSpecDefaultsException(
        "Invalid vidSamplingInterval",
        IllegalArgumentException("vidSamplingInterval.start cannot be negative.")
      )
    }
    if (vidSamplingInterval.start >= 1) {
      throw MetricSpecDefaultsException(
        "Invalid vidSamplingInterval",
        IllegalArgumentException("vidSamplingInterval.start must be smaller than 1.")
      )
    }
    if (vidSamplingInterval.width <= 0) {
      throw MetricSpecDefaultsException(
        "Invalid vidSamplingInterval",
        IllegalArgumentException("vidSamplingInterval.width must be greater than 0.")
      )
    }
    if (vidSamplingInterval.start + vidSamplingInterval.width > 1) {
      throw MetricSpecDefaultsException(
        "Invalid vidSamplingInterval",
        IllegalArgumentException("vidSamplingInterval start + width cannot be greater than 1.")
      )
    }
  }
}

/**
 * Specifies default values using [MetricSpecConfig] when optional fields in the
 * [MetricSpec.ReachParams] are not set.
 */
private fun MetricSpec.ReachParams.withDefaults(
  metricSpecConfig: MetricSpecConfig
): MetricSpec.ReachParams {
  if (!hasPrivacyParams()) {
    throw MetricSpecDefaultsException(
      "Invalid privacy params",
      IllegalArgumentException("privacyParams in reach is not set.")
    )
  }

  return copy {
    privacyParams =
      privacyParams.withDefaults(
        metricSpecConfig.reachParams.privacyParams.epsilon,
        metricSpecConfig.reachParams.privacyParams.delta
      )
  }
}

/**
 * Specifies default values using [MetricSpecConfig] when optional fields in the
 * [MetricSpec.FrequencyHistogramParams] are not set.
 */
private fun MetricSpec.FrequencyHistogramParams.withDefaults(
  metricSpecConfig: MetricSpecConfig
): MetricSpec.FrequencyHistogramParams {
  if (!hasReachPrivacyParams()) {
    throw MetricSpecDefaultsException(
      "Invalid privacy params",
      IllegalArgumentException("reachPrivacyParams in frequency histogram is not set.")
    )
  }
  if (!hasFrequencyPrivacyParams()) {
    throw MetricSpecDefaultsException(
      "Invalid privacy params",
      IllegalArgumentException("frequencyPrivacyParams in frequency histogram is not set.")
    )
  }

  return copy {
    reachPrivacyParams =
      reachPrivacyParams.withDefaults(
        metricSpecConfig.frequencyHistogramParams.reachPrivacyParams.epsilon,
        metricSpecConfig.frequencyHistogramParams.reachPrivacyParams.delta
      )
    frequencyPrivacyParams =
      frequencyPrivacyParams.withDefaults(
        metricSpecConfig.frequencyHistogramParams.frequencyPrivacyParams.epsilon,
        metricSpecConfig.frequencyHistogramParams.frequencyPrivacyParams.delta
      )
    maximumFrequencyPerUser =
      if (hasMaximumFrequencyPerUser()) {
        maximumFrequencyPerUser
      } else {
        metricSpecConfig.frequencyHistogramParams.maximumFrequencyPerUser
      }
  }
}

/**
 * Specifies default values using [MetricSpecConfig] when optional fields in the
 * [MetricSpec.WatchDurationParams] are not set.
 */
private fun MetricSpec.WatchDurationParams.withDefaults(
  metricSpecConfig: MetricSpecConfig
): MetricSpec.WatchDurationParams {
  if (!hasPrivacyParams()) {
    throw MetricSpecDefaultsException(
      "Invalid privacy params",
      IllegalArgumentException("privacyParams in watch duration is not set.")
    )
  }

  return copy {
    privacyParams =
      privacyParams.withDefaults(
        metricSpecConfig.watchDurationParams.privacyParams.epsilon,
        metricSpecConfig.watchDurationParams.privacyParams.delta
      )
    maximumWatchDurationPerUser =
      if (hasMaximumWatchDurationPerUser()) {
        maximumWatchDurationPerUser
      } else {
        metricSpecConfig.watchDurationParams.maximumWatchDurationPerUser
      }
  }
}

/**
 * Specifies default values using [MetricSpecConfig] when optional fields in the
 * [MetricSpec.ImpressionCountParams] are not set.
 */
private fun MetricSpec.ImpressionCountParams.withDefaults(
  metricSpecConfig: MetricSpecConfig
): MetricSpec.ImpressionCountParams {
  if (!hasPrivacyParams()) {
    throw MetricSpecDefaultsException(
      "Invalid privacy params",
      IllegalArgumentException("privacyParams in impression count is not set.")
    )
  }

  return copy {
    privacyParams =
      privacyParams.withDefaults(
        metricSpecConfig.impressionCountParams.privacyParams.epsilon,
        metricSpecConfig.impressionCountParams.privacyParams.delta
      )
    maximumFrequencyPerUser =
      if (hasMaximumFrequencyPerUser()) {
        maximumFrequencyPerUser
      } else {
        metricSpecConfig.impressionCountParams.maximumFrequencyPerUser
      }
  }
}

/**
 * Specifies the values in the optional fields of [MetricSpec.DifferentialPrivacyParams] when they
 * are not set.
 */
private fun MetricSpec.DifferentialPrivacyParams.withDefaults(
  defaultEpsilon: Double,
  defaultDelta: Double
): MetricSpec.DifferentialPrivacyParams {
  return copy {
    epsilon = if (hasEpsilon()) epsilon else defaultEpsilon
    delta = if (hasDelta()) delta else defaultDelta
  }
}

/** Converts an [MetricSpecConfig.VidSamplingInterval] to an [MetricSpec.VidSamplingInterval]. */
private fun MetricSpecConfig.VidSamplingInterval.toVidSamplingInterval():
  MetricSpec.VidSamplingInterval {
  val source = this
  return MetricSpecKt.vidSamplingInterval {
    start = source.start
    width = source.width
  }
}