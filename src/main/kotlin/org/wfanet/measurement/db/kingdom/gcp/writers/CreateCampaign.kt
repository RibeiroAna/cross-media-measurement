package org.wfanet.measurement.db.kingdom.gcp.writers

import com.google.cloud.spanner.Mutation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.common.InternalId
import org.wfanet.measurement.db.gcp.bufferTo
import org.wfanet.measurement.db.kingdom.gcp.readers.AdvertiserReader
import org.wfanet.measurement.db.kingdom.gcp.readers.DataProviderReader
import org.wfanet.measurement.internal.kingdom.Campaign

class CreateCampaign(
  private val externalDataProviderId: ExternalId,
  private val externalAdvertiserId: ExternalId,
  private val providedCampaignId: String
) : SpannerWriter<ExternalId, Campaign>() {
  override suspend fun TransactionScope.runTransaction(): ExternalId {
    val (dataProviderId, advertiserId) = coroutineScope {
      val dataProviderIed = async { readDataProviderId() }
      val advertiserId = async { readAdvertiserId() }
      Pair(dataProviderIed.await(), advertiserId.await())
    }
    val internalId = idGenerator.generateInternalId()
    val externalId = idGenerator.generateExternalId()
    Mutation.newInsertBuilder("Campaigns")
      .set("DataProviderId").to(dataProviderId.value)
      .set("CampaignId").to(internalId.value)
      .set("AdvertiserId").to(advertiserId.value)
      .set("ExternalCampaignId").to(externalId.value)
      .set("ProvidedCampaignId").to(providedCampaignId)
      .set("CampaignDetails").to("")
      .set("CampaignDetailsJson").to("")
      .build()
      .bufferTo(transactionContext)
    return externalId
  }

  override fun ResultScope<ExternalId>.buildResult(): Campaign {
    val externalCampaignId = checkNotNull(transactionResult).value
    return Campaign.newBuilder()
      .setExternalAdvertiserId(externalAdvertiserId.value)
      .setExternalDataProviderId(externalDataProviderId.value)
      .setExternalCampaignId(externalCampaignId)
      .setProvidedCampaignId(providedCampaignId)
      .build()
  }

  private suspend fun TransactionScope.readDataProviderId(): InternalId {
    val readResult = DataProviderReader().readExternalId(transactionContext, externalDataProviderId)
    return InternalId(readResult.dataProviderId)
  }

  private suspend fun TransactionScope.readAdvertiserId(): InternalId {
    val readResult = AdvertiserReader().readExternalId(transactionContext, externalAdvertiserId)
    return InternalId(readResult.advertiserId)
  }
}