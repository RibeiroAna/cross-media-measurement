package org.wfanet.measurement.integration

import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import java.math.BigInteger
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.wfanet.measurement.common.Duchy
import org.wfanet.measurement.common.DuchyOrder
import org.wfanet.measurement.common.identity.testing.DuchyIdSetter
import org.wfanet.measurement.common.testing.ProviderRule
import org.wfanet.measurement.common.testing.chainRulesSequentially
import org.wfanet.measurement.db.kingdom.KingdomRelationalDatabase
import org.wfanet.measurement.db.kingdom.streamReportsFilter
import org.wfanet.measurement.duchy.testing.TestKeys
import org.wfanet.measurement.integration.InProcessDuchy.DuchyDependencies
import org.wfanet.measurement.internal.kingdom.Report

val DUCHY_IDS = listOf("duchy1", "duchy2", "duchy3")

val EL_GAMAL_KEYS = DUCHY_IDS.zip(TestKeys.EL_GAMAL_KEYS).toMap()
val CLIENT_PUBLIC_KEY = TestKeys.COMBINED_EL_GAMAL_PUBLIC_KEY
const val CURVE_ID = TestKeys.CURVE_ID

val DUCHIES = EL_GAMAL_KEYS.map {
  Duchy(it.key, BigInteger(it.value.elGamalPk.toByteArray()))
}

val DUCHY_ORDER = DuchyOrder(DUCHIES.toSet())

/**
 * Test that everything is wired up properly.
 *
 * This is abstract so that different implementations of dependencies can all run the same tests
 * easily.
 */
abstract class InProcessKingdomAndDuchyIntegrationTest {
  /** Provides a [KingdomRelationalDatabase] to the test. */
  abstract val kingdomRelationalDatabaseRule: ProviderRule<KingdomRelationalDatabase>

  /** Provides a function from Duchy to the dependencies needed to start the Duchy to the test. */
  abstract val duchyDependenciesRule: ProviderRule<(Duchy) -> DuchyDependencies>

  private val kingdomRelationalDatabase: KingdomRelationalDatabase
    get() = kingdomRelationalDatabaseRule.value

  private val kingdom = InProcessKingdom(verboseGrpcLogging = false) { kingdomRelationalDatabase }

  private val duchies: List<InProcessDuchy> by lazy {
    DUCHIES.map { duchy ->
      InProcessDuchy(
        duchyId = duchy.name,
        otherDuchyIds = (DUCHY_IDS.toSet() - duchy.name).toList(),
        kingdomChannel = kingdom.publicApiChannel,
        duchyDependenciesProvider = { duchyDependenciesRule.value(duchy) }
      )
    }
  }

  private val dataProviderRule = FakeDataProviderRule("some-key-id")

  @get:Rule
  val ruleChain: TestRule by lazy {
    chainRulesSequentially(
      DuchyIdSetter(DUCHY_IDS),
      dataProviderRule,
      kingdomRelationalDatabaseRule,
      kingdom,
      duchyDependenciesRule,
      *duchies.toTypedArray()
    )
  }

  @Ignore // TODO: unignore this test case
  @Test
  fun `entire computation`() = runBlocking<Unit> {
    val (dataProviders, campaigns) = kingdom.populateKingdomRelationalDatabase()

    dataProviderRule.startDataProviderForCampaign(
      dataProviders[0], campaigns[0], duchies[0].newPublisherDataProviderStub()
    )

    dataProviderRule.startDataProviderForCampaign(
      dataProviders[1], campaigns[1], duchies[1].newPublisherDataProviderStub()
    )

    logger.info("Starting third data provider")
    dataProviderRule.startDataProviderForCampaign(
      dataProviders[1], campaigns[2], duchies[2].newPublisherDataProviderStub()
    )

    // Now wait until the computation is done.
    val doneReport: Report = pollFor(timeoutMillis = 10_000) {
      kingdomRelationalDatabase
        .streamReports(
          filter = streamReportsFilter(states = listOf(Report.ReportState.SUCCEEDED)),
          limit = 1
        )
        .singleOrNull()
    }

    assertThat(doneReport)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        Report.newBuilder().apply {
          reportDetailsBuilder.apply {
            addAllConfirmedDuchies(DUCHY_IDS)
            resultBuilder // Touch resultBuilder so there's some result
          }
        }.build()
      )
  }
}
