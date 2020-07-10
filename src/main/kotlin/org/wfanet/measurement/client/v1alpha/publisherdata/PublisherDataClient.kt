package org.wfanet.measurement.client.v1alpha.publisherdata

import io.grpc.ManagedChannel
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import org.wfanet.measurement.api.v1alpha.CombinedPublicKey
import org.wfanet.measurement.api.v1alpha.GetCombinedPublicKeyRequest
import org.wfanet.measurement.api.v1alpha.PublisherDataGrpcKt

class PublisherDataClient(
  private val channel: ManagedChannel,
  private val stub: PublisherDataGrpcKt.PublisherDataCoroutineStub
) :
  Closeable {

  override fun close() {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }

  suspend fun getCombinedPublicKey(): CombinedPublicKey = coroutineScope {
    println("Sending request to GetCombinedPublicKey...")
    val request = GetCombinedPublicKeyRequest.newBuilder().apply {
      keyBuilder.combinedPublicKeyId = "\"Ceci n'est pas une clé\""
    }.build()
    stub.getCombinedPublicKey(request)
  }
}
