package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiExtension
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiExtension.Companion.caseNotesApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonExtension
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonExtension.Companion.locationsInsidePrison
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension.Companion.prisonerSearch
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper
import java.time.Clock

@ExtendWith(
  HmppsAuthApiExtension::class,
  LocationsInsidePrisonExtension::class,
  PrisonApiExtension::class,
  CaseNotesApiExtension::class,
  PrisonerSearchExtension::class,
)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
abstract class IntegrationTestBase : TestBase() {

  @Autowired
  protected lateinit var webTestClient: WebTestClient

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthorisationHelper

  // Pins occurred_at so tests can assert on it. TestBase.clock is the single definition of "now".
  @MockitoBean
  private lateinit var clock: Clock

  @BeforeEach
  fun setUpClock() {
    whenever(clock.instant()).thenReturn(TestBase.clock.instant())
    whenever(clock.zone).thenReturn(TestBase.clock.zone)
  }

  internal fun setAuthorisation(
    username: String? = "AUTH_ADM",
    roles: List<String> = listOf(),
    scopes: List<String> = listOf("read"),
  ): (HttpHeaders) -> Unit = jwtAuthHelper.setAuthorisationHeader(username = username, scope = scopes, roles = roles)

  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
    prisonApi.stubHealthPing(status)
    caseNotesApi.stubHealthPing(status)
    prisonerSearch.stubHealthPing(status)
    locationsInsidePrison.stubHealthPing(status)
  }
}
