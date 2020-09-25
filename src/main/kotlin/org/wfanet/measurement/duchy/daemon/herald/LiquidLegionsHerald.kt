// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.wfanet.measurement.duchy.daemon.herald

import io.grpc.Status
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.wfanet.measurement.common.grpc.grpcStatusCode
import org.wfanet.measurement.common.throttler.Throttler
import org.wfanet.measurement.common.withRetriesOnEach
import org.wfanet.measurement.db.duchy.computation.LiquidLegionsSketchAggregationProtocol
import org.wfanet.measurement.db.duchy.computation.advanceLiquidLegionsComputationStage
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.COMPLETED
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.SKETCH_AGGREGATION_STAGE_UNKNOWN
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_ADD_NOISE
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_APPEND_SKETCHES_AND_ADD_NOISE
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_BLIND_POSITIONS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_BLIND_POSITIONS_AND_JOIN_REGISTERS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_CONFIRM_REQUISITIONS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_DECRYPT_FLAG_COUNTS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_DECRYPT_FLAG_COUNTS_AND_COMPUTE_METRICS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.UNRECOGNIZED
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_CONCATENATED
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_FLAG_COUNTS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_SKETCHES
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_TO_START
import org.wfanet.measurement.internal.duchy.ComputationBlobDependency.INPUT
import org.wfanet.measurement.internal.duchy.ComputationStorageServiceGrpcKt.ComputationStorageServiceCoroutineStub
import org.wfanet.measurement.internal.duchy.ComputationTypeEnum.ComputationType
import org.wfanet.measurement.internal.duchy.CreateComputationRequest
import org.wfanet.measurement.internal.duchy.ToConfirmRequisitionsStageDetails.RequisitionKey
import org.wfanet.measurement.service.internal.duchy.computation.storage.toGetTokenRequest
import org.wfanet.measurement.system.v1alpha.GlobalComputation.State
import org.wfanet.measurement.system.v1alpha.GlobalComputationsGrpcKt.GlobalComputationsCoroutineStub
import org.wfanet.measurement.system.v1alpha.StreamActiveGlobalComputationsRequest
import org.wfanet.measurement.system.v1alpha.StreamActiveGlobalComputationsResponse

/**
 * The Herald looks to the kingdom for status of computations.
 *
 * It is responsible for inserting new computations into the database, and for moving computations
 * out of the WAIT_TO_START stage once the kingdom has gotten confirmation from all duchies that
 * they are able to start the computation.
 *
 * @param computationStorageClient manages interactions with computations storage service.
 * @param globalComputationsClient stub for communicating with the Global Computations Service
 */
class LiquidLegionsHerald(
  otherDuchiesInComputation: List<String>,
  private val computationStorageClient: ComputationStorageServiceCoroutineStub,
  private val globalComputationsClient: GlobalComputationsCoroutineStub,
  private val maxStartAttempts: Int = 10
) {
  private val liquidLegionsStageDetails =
    LiquidLegionsSketchAggregationProtocol.EnumStages.Details(otherDuchiesInComputation)

  // If one of the GlobalScope coroutines launched by `start` fails, it populates this.
  private lateinit var startException: Throwable

  /**
   * Syncs the status of computations stored at the kingdom with those stored locally continually
   * in a forever loop. The [pollingThrottler] is used to limit how often the kingdom and
   * local computation storage service are polled.
   *
   * @param pollingThrottler throttles how often to get active computations from the Global
   * Computation Service
   */
  suspend fun continuallySyncStatuses(pollingThrottler: Throttler) {
    logger.info("Starting...")
    // Token signifying the last computation in an active state at the kingdom that was processed by
    // this job. When empty, all active computations at the kingdom will be streamed in the
    // response. The first execution of the loop will then compare all active computations at
    // the kingdom with all active computations locally.
    var lastProcessedContinuationToken = ""

    pollingThrottler.loopOnReady {
      lastProcessedContinuationToken = syncStatuses(lastProcessedContinuationToken)
    }
  }

  /**
   * Syncs the status of computations stored at the kingdom, via the global computation service,
   * with those stored locally.
   *
   * @param continuationToken the continuation token of the last computation in the stream which was
   * processed by the herald.
   * @return the continuation token of the last computation processed in that stream of active
   * computations from the global computation service.
   */
  suspend fun syncStatuses(continuationToken: String): String {
    if (this::startException.isInitialized) { throw startException }

    var lastProcessedContinuationToken = continuationToken
    logger.info("Reading stream of active computations since $continuationToken.")
    globalComputationsClient.streamActiveGlobalComputations(
      StreamActiveGlobalComputationsRequest.newBuilder()
        .setContinuationToken(continuationToken)
        .build()
    )
      .withRetriesOnEach(maxAttempts = 3, retryPredicate = ::mayBeTransientGrpcError) { response ->
        processGlobalComputationChange(response)
        lastProcessedContinuationToken = response.continuationToken
      }
      // Cancel the flow on the first error, but don't actually throw the error. This will keep
      // the continuation token at the last successfully processed item. A later execution of
      // syncStatuses() may be successful if the state at the kingdom and/or this duchy was updated.
      .catch { e -> logger.log(Level.SEVERE, "Exception:", e) }
      .collect()
    return lastProcessedContinuationToken
  }

  private suspend fun processGlobalComputationChange(
    response: StreamActiveGlobalComputationsResponse
  ) {
    val globalId: String = checkNotNull(response.globalComputation.key?.globalComputationId)
    logger.info("[id=$globalId]: Processing updated GlobalComputation")
    when (val state = response.globalComputation.state) {
      // Create a new computation if it is not already present in the database.
      State.CONFIRMING -> create(globalId, response.toRequisitionKeys())
      // Start the computation if it is in WAIT_TO_START.
      // TODO: Resume a computation that was once SUSPENDED.
      State.RUNNING -> start(globalId)
      State.SUSPENDED ->
        logger.warning("Pause/Resume of computations based on kingdom state not yet supported.")
      else ->
        logger.warning("Unexpected global computation state '$state'")
    }
  }

  /** Creates a new computation. */
  private suspend fun create(
    globalId: String,
    requisitionsAtThisDuchy: List<RequisitionKey>
  ) {
    logger.info("[id=$globalId] Creating Computation")
    try {
      computationStorageClient.createComputation(
        CreateComputationRequest.newBuilder().apply {
          computationType =
            COMPUTATION_TYPE
          globalComputationId = globalId
          stageDetailsBuilder
            .toConfirmRequisitionsStageDetailsBuilder
            .addAllKeys(requisitionsAtThisDuchy)
        }.build()
      )
      logger.info("[id=$globalId]: Created Computation")
    } catch (e: Exception) {
      if (e.grpcStatusCode() == Status.Code.ALREADY_EXISTS) {
        logger.info("[id=$globalId]: Computation already exists")
      } else {
        throw e // rethrow all other exceptions.
      }
    }
  }

  /**
   * Starts a computation that is in WAIT_TO_START.
   *
   * This immediately attempts once and if that failes, launches a coroutine to continue retrying
   * in the background.
   */
  private suspend fun start(globalId: String) {
    val attempt: suspend () -> Boolean = { runCatching { startAttempt(globalId) }.isSuccess }
    if (!attempt()) {
      GlobalScope.launch(Dispatchers.IO) {
        for (i in 2..maxStartAttempts) {
          logger.info("[id=$globalId] Attempt #$i to start")
          delay(timeMillis = minOf((1L shl i) * 1000L, 60_000L))
          if (attempt()) {
            return@launch
          }
        }
        val message = "[id=$globalId] Giving up after $maxStartAttempts attempts to start"
        logger.severe(message)
        startException = IllegalStateException(message)
      }
    }
  }

  /** Attempts to start a computation that is in WAIT_TO_START. */
  private suspend fun startAttempt(globalId: String) {
    logger.info("[id=$globalId]: Starting Computation")
    val token =
      computationStorageClient
        .getComputationToken(globalId.toGetTokenRequest(COMPUTATION_TYPE))
        .token

    when (val stage = token.computationStage.liquidLegionsSketchAggregation) {
      // We expect stage WAIT_TO_START.
      WAIT_TO_START -> {
        computationStorageClient.advanceLiquidLegionsComputationStage(
          computationToken = token,
          // The inputs of WAIT_TO_START are copies of the sketches stored locally. These are the very
          // sketches required for the TO_ADD_NOISE step of the computation.
          inputsToNextStage = token.blobsList.filter { it.dependencyType == INPUT }.map { it.path },
          stage = TO_ADD_NOISE,
          liquidLegionsStageDetails = liquidLegionsStageDetails
        )
        logger.info("[id=$globalId] Computation is now started")
        return
      }

      // For past stages, we throw.
      TO_CONFIRM_REQUISITIONS -> {
        error("[id=$globalId]: cannot start a computation still in state TO_CONFIRM_REQUISITIONS")
      }

      // For future stages, we log and exit.
      WAIT_SKETCHES,
      TO_ADD_NOISE,
      TO_APPEND_SKETCHES_AND_ADD_NOISE,
      WAIT_CONCATENATED,
      TO_BLIND_POSITIONS,
      TO_BLIND_POSITIONS_AND_JOIN_REGISTERS,
      WAIT_FLAG_COUNTS,
      TO_DECRYPT_FLAG_COUNTS,
      TO_DECRYPT_FLAG_COUNTS_AND_COMPUTE_METRICS,
      COMPLETED -> {
        logger.info("[id=$globalId]: not starting, stage '$stage' is after WAIT_TO_START")
        return
      }

      // For weird stages, we throw.
      UNRECOGNIZED,
      SKETCH_AGGREGATION_STAGE_UNKNOWN,
      null -> {
        error("[id=$globalId]: Unrecognized stage '$stage'")
      }
    }
  }

  companion object {
    val COMPUTATION_TYPE = ComputationType.LIQUID_LEGIONS_SKETCH_AGGREGATION_V1
    private val logger: Logger = Logger.getLogger(this::class.java.name)
  }
}

private fun StreamActiveGlobalComputationsResponse.toRequisitionKeys(): List<RequisitionKey> =
  globalComputation
    .metricRequisitionsList
    .map {
      RequisitionKey.newBuilder().apply {
        dataProviderId = it.dataProviderId
        campaignId = it.campaignId
        metricRequisitionId = it.metricRequisitionId
      }.build()
    }

/** Returns true if the error may be transient, i.e. retrying the request may succeed. */
fun mayBeTransientGrpcError(error: Throwable): Boolean {
  val statusCode = error.grpcStatusCode() ?: return false
  return when (statusCode) {
    Status.Code.ABORTED,
    Status.Code.DEADLINE_EXCEEDED,
    Status.Code.RESOURCE_EXHAUSTED,
    Status.Code.UNKNOWN,
    Status.Code.UNAVAILABLE -> true
    else -> false
  }
}