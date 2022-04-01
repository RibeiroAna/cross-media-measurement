// Copyright 2022 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.kingdom.deploy.gcloud.spanner.writers

import org.wfanet.measurement.common.identity.ExternalId
import org.wfanet.measurement.common.identity.InternalId
import org.wfanet.measurement.gcloud.spanner.bufferUpdateMutation
import org.wfanet.measurement.gcloud.spanner.set
import org.wfanet.measurement.gcloud.spanner.setJson
import org.wfanet.measurement.internal.kingdom.DataProvider
import org.wfanet.measurement.internal.kingdom.ErrorCode
import org.wfanet.measurement.internal.kingdom.MeasurementConsumer
import org.wfanet.measurement.internal.kingdom.UpdatePublicKeyRequest
import org.wfanet.measurement.internal.kingdom.copy
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.common.KingdomInternalException
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.readers.CertificateReader
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.readers.DataProviderReader
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.readers.MeasurementConsumerReader

/**
 * Updates the public key details for a [MeasurementConsumer] or a [DataProvider].
 *
 * Throws a [KingdomInternalException] on [execute] with the following codes/conditions:
 * * [ErrorCode.CERTIFICATE_NOT_FOUND]
 * * [ErrorCode.DATA_PROVIDER_NOT_FOUND]
 * * [ErrorCode.MEASUREMENT_CONSUMER_NOT_FOUND]
 */
class UpdatePublicKey(private val request: UpdatePublicKeyRequest) : SimpleSpannerWriter<Unit>() {

  override suspend fun TransactionScope.runTransaction() {
    if (request.externalMeasurementConsumerId != 0L) {
      val measurementConsumerResult =
        MeasurementConsumerReader()
          .readByExternalMeasurementConsumerId(
            transactionContext,
            ExternalId(request.externalMeasurementConsumerId)
          )
          ?: throw KingdomInternalException(ErrorCode.MEASUREMENT_CONSUMER_NOT_FOUND)

      CertificateReader(CertificateReader.ParentType.MEASUREMENT_CONSUMER)
        .readMeasurementConsumerCertificateIdByExternalId(
          transactionContext,
          InternalId(measurementConsumerResult.measurementConsumerId),
          ExternalId(request.externalCertificateId)
        )
        ?: throw KingdomInternalException(ErrorCode.CERTIFICATE_NOT_FOUND)

      val measurementConsumerDetails =
        measurementConsumerResult.measurementConsumer.details.copy {
          apiVersion = request.apiVersion
          publicKey = request.publicKey
          publicKeySignature = request.publicKeySignature
        }

      transactionContext.bufferUpdateMutation("MeasurementConsumers") {
        set("MeasurementConsumerId" to measurementConsumerResult.measurementConsumerId)
        set("MeasurementConsumerDetails" to measurementConsumerDetails)
        setJson("MeasurementConsumerDetailsJson" to measurementConsumerDetails)
      }
    } else if (request.externalDataProviderId != 0L) {
      val dataProviderResult =
        DataProviderReader()
          .readByExternalDataProviderId(
            transactionContext,
            ExternalId(request.externalDataProviderId)
          )
          ?: throw KingdomInternalException(ErrorCode.DATA_PROVIDER_NOT_FOUND)

      CertificateReader(CertificateReader.ParentType.DATA_PROVIDER)
        .readDataProviderCertificateIdByExternalId(
          transactionContext,
          InternalId(dataProviderResult.dataProviderId),
          ExternalId(request.externalCertificateId)
        )
        ?: throw KingdomInternalException(ErrorCode.CERTIFICATE_NOT_FOUND)

      val dataProviderDetails =
        dataProviderResult.dataProvider.details.copy {
          apiVersion = request.apiVersion
          publicKey = request.publicKey
          publicKeySignature = request.publicKeySignature
        }

      transactionContext.bufferUpdateMutation("DataProviders") {
        set("DataProviderId" to dataProviderResult.dataProviderId)
        set("DataProviderDetails" to dataProviderDetails)
        setJson("DataProviderDetailsJson" to dataProviderDetails)
      }
    }
  }
}