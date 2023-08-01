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

package org.wfanet.measurement.integration.common

import com.google.protobuf.ByteString
import java.security.cert.X509Certificate
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.wfanet.measurement.api.v2alpha.AccountsGrpcKt
import org.wfanet.measurement.api.v2alpha.ApiKeysGrpcKt
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt
import org.wfanet.measurement.common.crypto.subjectKeyIdentifier
import org.wfanet.measurement.common.crypto.tink.TinkPrivateKeyHandle
import org.wfanet.measurement.common.identity.DuchyInfo
import org.wfanet.measurement.common.testing.ProviderRule
import org.wfanet.measurement.common.testing.chainRulesSequentially
import org.wfanet.measurement.config.DuchyCertConfig
import org.wfanet.measurement.kingdom.deploy.common.DuchyIds
import org.wfanet.measurement.kingdom.deploy.common.Llv2ProtocolConfig
import org.wfanet.measurement.kingdom.deploy.common.RoLlv2ProtocolConfig
import org.wfanet.measurement.kingdom.deploy.common.service.DataServices
import org.wfanet.measurement.loadtest.measurementconsumer.MeasurementConsumerData
import org.wfanet.measurement.loadtest.resourcesetup.DuchyCert
import org.wfanet.measurement.loadtest.resourcesetup.EntityContent
import org.wfanet.measurement.loadtest.resourcesetup.ResourceSetup
import org.wfanet.measurement.storage.StorageClient

class InProcessCmmsComponents(
  private val kingdomDataServicesRule: ProviderRule<DataServices>,
  private val duchyDependenciesRule: ProviderRule<(String) -> InProcessDuchy.DuchyDependencies>,
  private val storageClient: StorageClient,
) : TestRule {
  private val kingdomDataServices: DataServices
    get() = kingdomDataServicesRule.value

  val kingdom: InProcessKingdom =
    InProcessKingdom(
      dataServicesProvider = { kingdomDataServices },
      verboseGrpcLogging = false,
      REDIRECT_URI,
    )

  private val duchies: List<InProcessDuchy> by lazy {
    ALL_DUCHY_NAMES.map {
      InProcessDuchy(
        externalDuchyId = it,
        kingdomSystemApiChannel = kingdom.systemApiChannel,
        duchyDependenciesProvider = { duchyDependenciesRule.value(it) },
        trustedCertificates = TRUSTED_CERTIFICATES,
        verboseGrpcLogging = false,
      )
    }
  }

  private val edpSimulators: List<InProcessEdpSimulator> by lazy {
    edpDisplayNameToResourceNameMap.entries.mapIndexed { index, (displayName, resourceName) ->
      val specIndex = index % SyntheticGenerationSpecs.SYNTHETIC_DATA_SPECS.size
      InProcessEdpSimulator(
        displayName = displayName,
        resourceName = resourceName,
        mcResourceName = mcResourceName,
        kingdomPublicApiChannel = kingdom.publicApiChannel,
        duchyPublicApiChannel = duchies[1].publicApiChannel,
        trustedCertificates = TRUSTED_CERTIFICATES,
        SyntheticGenerationSpecs.SYNTHETIC_DATA_SPECS[specIndex],
      )
    }
  }

  val ruleChain: TestRule by lazy {
    chainRulesSequentially(
      kingdomDataServicesRule,
      kingdom,
      duchyDependenciesRule,
      *duchies.toTypedArray()
    )
  }

  private val publicMeasurementConsumersClient by lazy {
    MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineStub(kingdom.publicApiChannel)
  }
  private val publicAccountsClient by lazy {
    AccountsGrpcKt.AccountsCoroutineStub(kingdom.publicApiChannel)
  }
  private val publicApiKeysClient by lazy {
    ApiKeysGrpcKt.ApiKeysCoroutineStub(kingdom.publicApiChannel)
  }

  private lateinit var mcResourceName: String
  private lateinit var apiAuthenticationKey: String
  private lateinit var edpDisplayNameToResourceNameMap: Map<String, String>
  private lateinit var duchyCertMap: Map<String, String>
  private lateinit var eventGroups: List<EventGroup>

  private suspend fun createAllResources() {
    val resourceSetup =
      ResourceSetup(
        internalAccountsClient = kingdom.internalAccountsClient,
        internalDataProvidersClient = kingdom.internalDataProvidersClient,
        accountsClient = publicAccountsClient,
        apiKeysClient = publicApiKeysClient,
        internalCertificatesClient = kingdom.internalCertificatesClient,
        measurementConsumersClient = publicMeasurementConsumersClient,
        runId = "12345",
        requiredDuchies = listOf("worker1", "worker2")
      )
    // Create the MC.
    val (measurementConsumer, apiKey) =
      resourceSetup.createMeasurementConsumer(
        MC_ENTITY_CONTENT,
        resourceSetup.createAccountWithRetries()
      )
    mcResourceName = measurementConsumer.name
    apiAuthenticationKey = apiKey
    // Create all EDPs
    edpDisplayNameToResourceNameMap =
      ALL_EDP_DISPLAY_NAMES.associateWith {
        val edp = createEntityContent(it)
        resourceSetup.createInternalDataProvider(edp)
      }
    // Create all duchy certificates.
    duchyCertMap =
      ALL_DUCHY_NAMES.associateWith {
        resourceSetup
          .createDuchyCertificate(DuchyCert(it, loadTestCertDerFile("${it}_cs_cert.der")))
          .name
      }
  }

  fun getMeasurementConsumerData(): MeasurementConsumerData {
    return MeasurementConsumerData(
      mcResourceName,
      MC_ENTITY_CONTENT.signingKey,
      MC_ENCRYPTION_PRIVATE_KEY,
      apiAuthenticationKey
    )
  }

  fun startDaemons() = runBlocking {
    // Create all resources
    createAllResources()
    eventGroups = edpSimulators.map { it.ensureEventGroup() }

    // Start daemons. Mills and EDP simulators can only be started after resources have been
    // created.
    duchies.forEach {
      it.startHerald()
      it.startLiquidLegionsV2mill(duchyCertMap)
    }
    edpSimulators.forEach { it.start() }
  }

  fun stopEdpSimulators() = runBlocking { edpSimulators.forEach { it.stop() } }

  fun stopDuchyDaemons() = runBlocking {
    for (duchy in duchies) {
      duchy.stopHerald()
      duchy.stopLiquidLegionsV2Mill()
    }
  }

  override fun apply(statement: Statement, description: Description): Statement {
    return ruleChain.apply(statement, description)
  }

  companion object {
    private const val REDIRECT_URI = "https://localhost:2048"
    val MC_ENTITY_CONTENT: EntityContent = createEntityContent(MC_DISPLAY_NAME)
    val MC_ENCRYPTION_PRIVATE_KEY: TinkPrivateKeyHandle =
      loadEncryptionPrivateKey("${MC_DISPLAY_NAME}_enc_private.tink")

    val TRUSTED_CERTIFICATES: Map<ByteString, X509Certificate> =
      loadTestCertCollection("all_root_certs.pem").associateBy {
        checkNotNull(it.subjectKeyIdentifier)
      }

    @JvmStatic
    fun initConfig() {
      DuchyIds.setForTest(ALL_DUCHIES)
      Llv2ProtocolConfig.setForTest(
        LLV2_PROTOCOL_CONFIG_CONFIG.protocolConfig,
        LLV2_PROTOCOL_CONFIG_CONFIG.duchyProtocolConfig,
        setOf("aggregator"),
        2
      )
      RoLlv2ProtocolConfig.setForTest(
        LLV2_PROTOCOL_CONFIG_CONFIG.protocolConfig,
        LLV2_PROTOCOL_CONFIG_CONFIG.duchyProtocolConfig,
        setOf("aggregator"),
        2,
        false
      )
      DuchyInfo.initializeFromConfig(
        loadTextProto("duchy_cert_config.textproto", DuchyCertConfig.getDefaultInstance())
      )
    }
  }
}
