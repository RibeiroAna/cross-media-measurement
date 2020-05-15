package org.wfanet.measurement.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertFailsWith

@RunWith(JUnit4::class)
class IdentifiersTest {

  @Test
  fun `round trips`() {
    for (i in listOf<Long>(0, 1, 10, 64, 1 shl 32, Long.MAX_VALUE)) {
      val externalId1 = ExternalId(i)
      val apiId1 = externalId1.apiId
      val externalId2 = ApiId(apiId1.value).externalId
      val apiId2 = externalId2.apiId

      assertThat(apiId1.value).isEqualTo(apiId2.value)
      assertThat(externalId1.value).isEqualTo(externalId2.value)
    }
  }

  @Test
  fun `negative numbers are invalid`() {
    assertFailsWith<IllegalArgumentException> {
      ExternalId(-1)
    }
  }
}