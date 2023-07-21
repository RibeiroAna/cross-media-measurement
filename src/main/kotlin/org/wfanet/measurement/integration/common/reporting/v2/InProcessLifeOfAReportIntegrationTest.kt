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

package org.wfanet.measurement.integration.common.reporting.v2

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.duration
import com.google.protobuf.timestamp
import com.google.type.interval
import java.io.File
import java.nio.file.Paths
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineStub
import org.wfanet.measurement.api.v2alpha.getMeasurementConsumerRequest
import org.wfanet.measurement.api.withAuthenticationKey
import org.wfanet.measurement.common.crypto.readCertificateCollection
import org.wfanet.measurement.common.crypto.subjectKeyIdentifier
import org.wfanet.measurement.common.getRuntimePath
import org.wfanet.measurement.common.testing.ProviderRule
import org.wfanet.measurement.common.testing.chainRulesSequentially
import org.wfanet.measurement.config.reporting.EncryptionKeyPairConfig
import org.wfanet.measurement.config.reporting.EncryptionKeyPairConfigKt.keyPair
import org.wfanet.measurement.config.reporting.EncryptionKeyPairConfigKt.principalKeyPairs
import org.wfanet.measurement.config.reporting.MeasurementConsumerConfig
import org.wfanet.measurement.config.reporting.encryptionKeyPairConfig
import org.wfanet.measurement.config.reporting.measurementConsumerConfig
import org.wfanet.measurement.integration.common.InProcessCmmsComponents
import org.wfanet.measurement.integration.common.InProcessDuchy
import org.wfanet.measurement.integration.common.reporting.v2.identity.withPrincipalName
import org.wfanet.measurement.kingdom.deploy.common.service.DataServices
import org.wfanet.measurement.reporting.deploy.v2.common.server.InternalReportingServer
import org.wfanet.measurement.reporting.service.api.v2alpha.withDefaults
import org.wfanet.measurement.reporting.v2alpha.EventGroup
import org.wfanet.measurement.reporting.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub
import org.wfanet.measurement.reporting.v2alpha.Metric
import org.wfanet.measurement.reporting.v2alpha.MetricSpecKt
import org.wfanet.measurement.reporting.v2alpha.MetricsGrpcKt.MetricsCoroutineStub
import org.wfanet.measurement.reporting.v2alpha.Report
import org.wfanet.measurement.reporting.v2alpha.ReportKt
import org.wfanet.measurement.reporting.v2alpha.ReportingSet
import org.wfanet.measurement.reporting.v2alpha.ReportingSetKt
import org.wfanet.measurement.reporting.v2alpha.ReportingSetsGrpcKt.ReportingSetsCoroutineStub
import org.wfanet.measurement.reporting.v2alpha.ReportsGrpcKt.ReportsCoroutineStub
import org.wfanet.measurement.reporting.v2alpha.createMetricRequest
import org.wfanet.measurement.reporting.v2alpha.createReportRequest
import org.wfanet.measurement.reporting.v2alpha.createReportingSetRequest
import org.wfanet.measurement.reporting.v2alpha.getMetricRequest
import org.wfanet.measurement.reporting.v2alpha.getReportRequest
import org.wfanet.measurement.reporting.v2alpha.listEventGroupsRequest
import org.wfanet.measurement.reporting.v2alpha.listMetricsRequest
import org.wfanet.measurement.reporting.v2alpha.listReportingSetsRequest
import org.wfanet.measurement.reporting.v2alpha.listReportsRequest
import org.wfanet.measurement.reporting.v2alpha.metric
import org.wfanet.measurement.reporting.v2alpha.metricSpec
import org.wfanet.measurement.reporting.v2alpha.periodicTimeInterval
import org.wfanet.measurement.reporting.v2alpha.report
import org.wfanet.measurement.reporting.v2alpha.reportingSet
import org.wfanet.measurement.reporting.v2alpha.timeIntervals
import org.wfanet.measurement.storage.StorageClient

/**
 * Test that everything is wired up properly.
 *
 * This is abstract so that different implementations of dependencies can all run the same tests
 * easily.
 */
abstract class InProcessLifeOfAReportIntegrationTest {
  abstract val kingdomDataServicesRule: ProviderRule<DataServices>

  /** Provides a function from Duchy to the dependencies needed to start the Duchy to the test. */
  abstract val duchyDependenciesRule: ProviderRule<(String) -> InProcessDuchy.DuchyDependencies>

  abstract val storageClient: StorageClient

  private val inProcessCmmsComponents: InProcessCmmsComponents by lazy {
    InProcessCmmsComponents(kingdomDataServicesRule, duchyDependenciesRule, storageClient)
  }

  private val inProcessCmmsComponentsStartup: TestRule by lazy {
    TestRule { statement, _ ->
      object : Statement() {
        override fun evaluate() {
          inProcessCmmsComponents.startDaemons()
          statement.evaluate()
        }
      }
    }
  }

  abstract val internalReportingServerServices: InternalReportingServer.Services

  private val reportingServer: InProcessReportingServer by lazy {
    val encryptionKeyPairConfigGenerator: () -> EncryptionKeyPairConfig = {
      val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()

      encryptionKeyPairConfig {
        principalKeyPairs += principalKeyPairs {
          principal = measurementConsumerData.name
          keyPairs += keyPair {
            publicKeyFile = "mc_enc_public.tink"
            privateKeyFile = "mc_enc_private.tink"
          }
        }
      }
    }

    val measurementConsumerConfigGenerator: suspend () -> MeasurementConsumerConfig = {
      val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()

      val measurementConsumer =
        publicKingdomMeasurementConsumersClient
          .withAuthenticationKey(measurementConsumerData.apiAuthenticationKey)
          .getMeasurementConsumer(
            getMeasurementConsumerRequest { name = measurementConsumerData.name }
          )

      measurementConsumerConfig {
        apiKey = measurementConsumerData.apiAuthenticationKey
        signingCertificateName = measurementConsumer.certificate
        signingPrivateKeyPath = MC_SIGNING_PRIVATE_KEY_PATH
      }
    }

    InProcessReportingServer(
      internalReportingServerServices,
      { inProcessCmmsComponents.kingdom.publicApiChannel },
      encryptionKeyPairConfigGenerator,
      SECRETS_DIR,
      measurementConsumerConfigGenerator,
      TRUSTED_CERTIFICATES,
      verboseGrpcLogging = false,
    )
  }

  @get:Rule
  val ruleChain: TestRule by lazy {
    chainRulesSequentially(inProcessCmmsComponents, inProcessCmmsComponentsStartup, reportingServer)
  }

  private val publicKingdomMeasurementConsumersClient by lazy {
    MeasurementConsumersCoroutineStub(inProcessCmmsComponents.kingdom.publicApiChannel)
  }

  private val publicEventGroupsClient by lazy {
    EventGroupsCoroutineStub(reportingServer.publicApiChannel)
  }

  private val publicMetricsClient by lazy { MetricsCoroutineStub(reportingServer.publicApiChannel) }

  private val publicReportsClient by lazy { ReportsCoroutineStub(reportingServer.publicApiChannel) }

  private val publicReportingSetsClient by lazy {
    ReportingSetsCoroutineStub(reportingServer.publicApiChannel)
  }

  @After
  fun stopEdpSimulators() {
    inProcessCmmsComponents.stopEdpSimulators()
  }

  @After
  fun stopDuchyDaemons() {
    inProcessCmmsComponents.stopDuchyDaemons()
  }

  @Test
  fun `report with union reach has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.UNION
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet.name
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "union reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                }
            }
        }
      timeIntervals = timeIntervals {
        timeIntervals += interval {
          startTime = timestamp { seconds = 100 }
          endTime = timestamp { seconds = 200 }
        }
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `report with unique reach has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.DIFFERENCE
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  expression =
                    ReportingSetKt.setExpression {
                      operation = ReportingSet.SetExpression.Operation.UNION
                      lhs =
                        ReportingSetKt.SetExpressionKt.operand {
                          reportingSet = createdPrimitiveReportingSet.name
                        }
                      rhs =
                        ReportingSetKt.SetExpressionKt.operand {
                          reportingSet = createdPrimitiveReportingSet2.name
                        }
                    }
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "unique reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                }
            }
        }
      timeIntervals = timeIntervals {
        timeIntervals += interval {
          startTime = timestamp { seconds = 100 }
          endTime = timestamp { seconds = 200 }
        }
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `report with intersection reach has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.INTERSECTION
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet.name
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "intersection reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                }
            }
        }
      timeIntervals = timeIntervals {
        timeIntervals += interval {
          startTime = timestamp { seconds = 100 }
          endTime = timestamp { seconds = 200 }
        }
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `report across two time intervals has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.UNION
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet.name
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "union reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                }
            }
        }
      timeIntervals = timeIntervals {
        timeIntervals += interval {
          startTime = timestamp { seconds = 100 }
          endTime = timestamp { seconds = 200 }
        }

        timeIntervals += interval {
          startTime = timestamp { seconds = 300 }
          endTime = timestamp { seconds = 400 }
        }
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `report with periodic time interval has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.UNION
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet.name
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "union reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                }
            }
        }
      periodicTimeInterval = periodicTimeInterval {
        startTime = timestamp { seconds = 100 }
        increment = duration { seconds = 10 }
        intervalCount = 10
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `report with cumulative has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.UNION
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet.name
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "union reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                  cumulative = true
                }
            }
        }
      periodicTimeInterval = periodicTimeInterval {
        startTime = timestamp { seconds = 100 }
        increment = duration { seconds = 10 }
        intervalCount = 10
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `report with group by has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val primitiveReportingSet2 = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 2"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet2 =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet2
            reportingSetId = "abc2"
          }
        )

    val compositeReportingSet = reportingSet {
      displayName = "composite"
      filter = "person.age_group == 1"
      composite =
        ReportingSetKt.composite {
          expression =
            ReportingSetKt.setExpression {
              operation = ReportingSet.SetExpression.Operation.UNION
              lhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet.name
                }
              rhs =
                ReportingSetKt.SetExpressionKt.operand {
                  reportingSet = createdPrimitiveReportingSet2.name
                }
            }
        }
    }

    val createdCompositeReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = compositeReportingSet
            reportingSetId = "def"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdCompositeReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "union reach"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                  groupings +=
                    ReportKt.grouping {
                      predicates += "person.age_group == 2"
                      predicates += "person.age_group == 1"
                    }
                  groupings +=
                    ReportKt.grouping {
                      predicates += "person.gender == 2"
                      predicates += "person.gender == 1"
                    }
                }
            }
        }
      timeIntervals = timeIntervals {
        timeIntervals += interval {
          startTime = timestamp { seconds = 100 }
          endTime = timestamp { seconds = 200 }
        }

        timeIntervals += interval {
          startTime = timestamp { seconds = 300 }
          endTime = timestamp { seconds = 400 }
        }
      }
    }

    val createdReport =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReport(
          createReportRequest {
            parent = measurementConsumerData.name
            this.report = report
            reportId = "report"
          }
        )

    val retrievedReport = pollForCompletedReport(measurementConsumerData.name, createdReport.name)
    assertThat(retrievedReport.state).isEqualTo(Report.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `creating 25 reports at once succeeds`() = runBlocking {
    val numReports = 25
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val report = report {
      reportingMetricEntries +=
        ReportKt.reportingMetricEntry {
          key = createdPrimitiveReportingSet.name
          value =
            ReportKt.reportingMetricCalculationSpec {
              metricCalculationSpecs +=
                ReportKt.metricCalculationSpec {
                  displayName = "load test"
                  metricSpecs +=
                    metricSpec {
                        reach =
                          MetricSpecKt.reachParams {
                            privacyParams = MetricSpecKt.differentialPrivacyParams {}
                          }
                      }
                      .withDefaults(reportingServer.metricSpecConfig)
                }
            }
        }
      timeIntervals = timeIntervals {
        timeIntervals += interval {
          startTime = timestamp { seconds = 100 }
          endTime = timestamp { seconds = 200 }
        }
      }
    }

    val deferred: MutableList<Deferred<Report>> = mutableListOf()
    repeat(numReports) {
      deferred.add(
        async {
          publicReportsClient
            .withPrincipalName(measurementConsumerData.name)
            .createReport(
              createReportRequest {
                parent = measurementConsumerData.name
                this.report = report
                reportId = "report$it"
              }
            )
        }
      )
    }

    deferred.awaitAll()
    val retrievedReports =
      publicReportsClient
        .withPrincipalName(measurementConsumerData.name)
        .listReports(
          listReportsRequest {
            parent = measurementConsumerData.name
            pageSize = numReports
          }
        )
        .reportsList

    assertThat(retrievedReports).hasSize(numReports)
    retrievedReports.forEach {
      assertThat(it)
        .ignoringFields(
          Report.NAME_FIELD_NUMBER,
          Report.STATE_FIELD_NUMBER,
          Report.CREATE_TIME_FIELD_NUMBER,
          Report.METRIC_CALCULATION_RESULTS_FIELD_NUMBER
        )
        .isEqualTo(report)
    }
  }

  @Test
  fun `reach metric result has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val metric = metric {
      reportingSet = createdPrimitiveReportingSet.name
      timeInterval = interval {
        startTime = timestamp { seconds = 100 }
        endTime = timestamp { seconds = 200 }
      }
      metricSpec =
        metricSpec {
            reach =
              MetricSpecKt.reachParams { privacyParams = MetricSpecKt.differentialPrivacyParams {} }
          }
          .withDefaults(reportingServer.metricSpecConfig)
    }

    val createdMetric =
      publicMetricsClient
        .withPrincipalName(measurementConsumerData.name)
        .createMetric(
          createMetricRequest {
            parent = measurementConsumerData.name
            this.metric = metric
            metricId = "abc"
          }
        )

    val retrievedMetric = pollForCompletedMetric(measurementConsumerData.name, createdMetric.name)
    assertThat(retrievedMetric.state).isEqualTo(Metric.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `frequency histogram metric has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val metric = metric {
      reportingSet = createdPrimitiveReportingSet.name
      timeInterval = interval {
        startTime = timestamp { seconds = 100 }
        endTime = timestamp { seconds = 200 }
      }
      metricSpec =
        metricSpec {
            frequencyHistogram =
              MetricSpecKt.frequencyHistogramParams {
                reachPrivacyParams = MetricSpecKt.differentialPrivacyParams {}
                frequencyPrivacyParams = MetricSpecKt.differentialPrivacyParams {}
              }
          }
          .withDefaults(reportingServer.metricSpecConfig)
    }

    val createdMetric =
      publicMetricsClient
        .withPrincipalName(measurementConsumerData.name)
        .createMetric(
          createMetricRequest {
            parent = measurementConsumerData.name
            this.metric = metric
            metricId = "abc"
          }
        )

    val retrievedMetric = pollForCompletedMetric(measurementConsumerData.name, createdMetric.name)
    assertThat(retrievedMetric.state).isEqualTo(Metric.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `impression count metric has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val metric = metric {
      reportingSet = createdPrimitiveReportingSet.name
      timeInterval = interval {
        startTime = timestamp { seconds = 100 }
        endTime = timestamp { seconds = 200 }
      }
      metricSpec =
        metricSpec {
            impressionCount =
              MetricSpecKt.impressionCountParams {
                privacyParams = MetricSpecKt.differentialPrivacyParams {}
              }
          }
          .withDefaults(reportingServer.metricSpecConfig)
    }

    val createdMetric =
      publicMetricsClient
        .withPrincipalName(measurementConsumerData.name)
        .createMetric(
          createMetricRequest {
            parent = measurementConsumerData.name
            this.metric = metric
            metricId = "abc"
          }
        )

    val retrievedMetric = pollForCompletedMetric(measurementConsumerData.name, createdMetric.name)
    assertThat(retrievedMetric.state).isEqualTo(Metric.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Ignore
  @Test
  fun `watch duration metric has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val metric = metric {
      reportingSet = createdPrimitiveReportingSet.name
      timeInterval = interval {
        startTime = timestamp { seconds = 100 }
        endTime = timestamp { seconds = 200 }
      }
      metricSpec =
        metricSpec {
            watchDuration =
              MetricSpecKt.watchDurationParams {
                privacyParams = MetricSpecKt.differentialPrivacyParams {}
              }
          }
          .withDefaults(reportingServer.metricSpecConfig)
    }

    val createdMetric =
      publicMetricsClient
        .withPrincipalName(measurementConsumerData.name)
        .createMetric(
          createMetricRequest {
            parent = measurementConsumerData.name
            this.metric = metric
            metricId = "abc"
          }
        )

    val retrievedMetric = pollForCompletedMetric(measurementConsumerData.name, createdMetric.name)
    assertThat(retrievedMetric.state).isEqualTo(Metric.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `reach metric with filter has the expected result`() = runBlocking {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val metric = metric {
      reportingSet = createdPrimitiveReportingSet.name
      timeInterval = interval {
        startTime = timestamp { seconds = 100 }
        endTime = timestamp { seconds = 200 }
      }
      metricSpec =
        metricSpec {
            reach =
              MetricSpecKt.reachParams { privacyParams = MetricSpecKt.differentialPrivacyParams {} }
          }
          .withDefaults(reportingServer.metricSpecConfig)
      filters += "person.gender == 1"
    }

    val createdMetric =
      publicMetricsClient
        .withPrincipalName(measurementConsumerData.name)
        .createMetric(
          createMetricRequest {
            parent = measurementConsumerData.name
            this.metric = metric
            metricId = "abc"
          }
        )

    val retrievedMetric = pollForCompletedMetric(measurementConsumerData.name, createdMetric.name)
    assertThat(retrievedMetric.state).isEqualTo(Metric.State.SUCCEEDED)

    // TODO(@tristanvuong2021): calculate expected result and compare once synthetic event groups
    // is implemented.
  }

  @Test
  fun `creating 25 metrics at once succeeds`() = runBlocking {
    val numMetrics = 25
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val createdPrimitiveReportingSet =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .createReportingSet(
          createReportingSetRequest {
            parent = measurementConsumerData.name
            reportingSet = primitiveReportingSet
            reportingSetId = "abc"
          }
        )

    val metric = metric {
      reportingSet = createdPrimitiveReportingSet.name
      timeInterval = interval {
        startTime = timestamp { seconds = 100 }
        endTime = timestamp { seconds = 200 }
      }
      metricSpec =
        metricSpec {
            reach =
              MetricSpecKt.reachParams { privacyParams = MetricSpecKt.differentialPrivacyParams {} }
          }
          .withDefaults(reportingServer.metricSpecConfig)
    }

    val deferred: MutableList<Deferred<Metric>> = mutableListOf()
    repeat(numMetrics) {
      deferred.add(
        async {
          publicMetricsClient
            .withPrincipalName(measurementConsumerData.name)
            .createMetric(
              createMetricRequest {
                parent = measurementConsumerData.name
                this.metric = metric
                metricId = "abc$it"
              }
            )
        }
      )
    }

    deferred.awaitAll()
    val retrievedMetrics =
      publicMetricsClient
        .withPrincipalName(measurementConsumerData.name)
        .listMetrics(
          listMetricsRequest {
            parent = measurementConsumerData.name
            pageSize = numMetrics
          }
        )
        .metricsList

    assertThat(retrievedMetrics).hasSize(numMetrics)
    retrievedMetrics.forEach {
      assertThat(it)
        .ignoringFields(
          Metric.NAME_FIELD_NUMBER,
          Metric.STATE_FIELD_NUMBER,
          Metric.CREATE_TIME_FIELD_NUMBER,
          Metric.RESULT_FIELD_NUMBER
        )
        .isEqualTo(metric)
    }
  }

  @Test
  fun `creating 25 reporting sets at once succeeds`() = runBlocking {
    val numReportingSets = 25
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()
    val eventGroups = listEventGroups()

    val primitiveReportingSet = reportingSet {
      displayName = "primitive"
      filter = "person.age_group == 1"
      primitive = ReportingSetKt.primitive { cmmsEventGroups += eventGroups[0].cmmsEventGroup }
    }

    val deferred: MutableList<Deferred<ReportingSet>> = mutableListOf()
    repeat(numReportingSets) {
      deferred.add(
        async {
          publicReportingSetsClient
            .withPrincipalName(measurementConsumerData.name)
            .createReportingSet(
              createReportingSetRequest {
                parent = measurementConsumerData.name
                reportingSet = primitiveReportingSet
                reportingSetId = "abc$it"
              }
            )
        }
      )
    }

    deferred.awaitAll()
    val retrievedPrimitiveReportingSets =
      publicReportingSetsClient
        .withPrincipalName(measurementConsumerData.name)
        .listReportingSets(
          listReportingSetsRequest {
            parent = measurementConsumerData.name
            pageSize = numReportingSets
          }
        )
        .reportingSetsList

    assertThat(retrievedPrimitiveReportingSets).hasSize(numReportingSets)
    retrievedPrimitiveReportingSets.forEach {
      assertThat(it).ignoringFields(ReportingSet.NAME_FIELD_NUMBER).isEqualTo(primitiveReportingSet)
    }
  }

  private suspend fun listEventGroups(): List<EventGroup> {
    val measurementConsumerData = inProcessCmmsComponents.getMeasurementConsumerData()

    return publicEventGroupsClient
      .withPrincipalName(measurementConsumerData.name)
      .listEventGroups(
        listEventGroupsRequest {
          parent = measurementConsumerData.name
          pageSize = 1000
        }
      )
      .eventGroupsList
  }

  private suspend fun pollForCompletedReport(
    measurementConsumerName: String,
    reportName: String
  ): Report {
    while (true) {
      val retrievedReport =
        publicReportsClient
          .withPrincipalName(measurementConsumerName)
          .getReport(getReportRequest { name = reportName })

      @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA") // Proto enum fields are never null.
      when (retrievedReport.state) {
        Report.State.SUCCEEDED,
        Report.State.FAILED -> return retrievedReport
        Report.State.RUNNING,
        Report.State.UNRECOGNIZED,
        Report.State.STATE_UNSPECIFIED -> delay(5000)
      }
    }
  }

  private suspend fun pollForCompletedMetric(
    measurementConsumerName: String,
    metricName: String
  ): Metric {
    while (true) {
      val retrievedMetric =
        publicMetricsClient
          .withPrincipalName(measurementConsumerName)
          .getMetric(getMetricRequest { name = metricName })

      @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA") // Proto enum fields are never null.
      when (retrievedMetric.state) {
        Metric.State.SUCCEEDED,
        Metric.State.FAILED -> return retrievedMetric
        Metric.State.RUNNING,
        Metric.State.UNRECOGNIZED,
        Metric.State.STATE_UNSPECIFIED -> delay(5000)
      }
    }
  }

  companion object {
    private val SECRETS_DIR: File =
      getRuntimePath(
          Paths.get(
            "wfa_measurement_system",
            "src",
            "main",
            "k8s",
            "testing",
            "secretfiles",
          )
        )!!
        .toFile()

    private val TRUSTED_CERTIFICATES =
      readCertificateCollection(SECRETS_DIR.resolve("all_root_certs.pem")).associateBy {
        it.subjectKeyIdentifier!!
      }

    private const val MC_SIGNING_PRIVATE_KEY_PATH = "mc_cs_private.der"

    @BeforeClass
    @JvmStatic
    fun initConfig() {
      InProcessCmmsComponents.initConfig()
    }
  }
}