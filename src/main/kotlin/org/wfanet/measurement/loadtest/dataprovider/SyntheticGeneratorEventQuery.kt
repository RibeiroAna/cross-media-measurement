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

package org.wfanet.measurement.loadtest.dataprovider

import com.google.protobuf.Descriptors
import org.projectnessie.cel.Program
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.event_group_metadata.testing.SyntheticEventGroupSpec
import org.wfanet.measurement.api.v2alpha.event_group_metadata.testing.SyntheticPopulationSpec
import org.wfanet.measurement.api.v2alpha.event_templates.testing.TestEvent
import org.wfanet.measurement.common.toRange
import org.wfanet.measurement.eventdataprovider.eventfiltration.EventFilters

/** [EventQuery] that uses [SyntheticDataGeneration]. */
abstract class SyntheticGeneratorEventQuery(
  private val populationSpec: SyntheticPopulationSpec,
) : EventQuery {
  /** Returns the synthetic data spec for [eventGroup]. */
  abstract fun getSyntheticDataSpec(eventGroup: EventGroup): SyntheticEventGroupSpec

  override fun getUserVirtualIds(eventGroupSpec: EventQuery.EventGroupSpec): Sequence<Long> {
    val timeRange = eventGroupSpec.spec.collectionInterval.toRange()
    val syntheticDataSpec: SyntheticEventGroupSpec = getSyntheticDataSpec(eventGroupSpec.eventGroup)
    val program: Program = EventQuery.compileProgram(eventGroupSpec.spec.filter, EVENT_MESSAGE_TYPE)
    return SyntheticDataGeneration.generateEvents(
        EVENT_MESSAGE_TYPE,
        populationSpec,
        syntheticDataSpec
      )
      .filter { it.timestamp in timeRange }
      .filter { EventFilters.matches(it.event, program) }
      .map { it.vid }
  }

  companion object {
    private val EVENT_MESSAGE_TYPE: Descriptors.Descriptor = TestEvent.getDescriptor()
  }
}
