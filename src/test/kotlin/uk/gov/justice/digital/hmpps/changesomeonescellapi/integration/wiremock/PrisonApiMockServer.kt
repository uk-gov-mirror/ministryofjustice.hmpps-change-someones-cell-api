package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonApi = PrisonApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonApi.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonApi.stop()
  }
}

class PrisonApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8091
  }

  fun stubHealthPing(status: Int) {
    stubFor(
      get("/health/ping").willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(if (status == 200) """{"status":"UP"}""" else """{"status":"DOWN"}""")
          .withStatus(status),
      ),
    )
  }

  /**
   * The cell move. Matched on the URL path only, so a test asserting the query string (reasonCode,
   * lockTimeout) does so explicitly rather than relying on the stub to fail silently.
   */
  fun stubMoveToCell(bookingId: Long, locationKey: String, bedAssignmentHistorySequence: Int? = 2) {
    stubFor(
      put(urlPathEqualTo("/api/bookings/$bookingId/living-unit/$locationKey")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "bookingId": $bookingId,
                "agencyId": "MDI",
                "assignedLivingUnitId": 25700,
                "assignedLivingUnitDesc": "$locationKey",
                "bedAssignmentHistorySequence": $bedAssignmentHistorySequence
              }
            """.trimIndent(),
          )
          .withStatus(200),
      ),
    )
  }

  /** Any failure from the move, e.g. 400 for a full cell or 423 when the record is open in P-NOMIS. */
  fun stubMoveToCellFails(bookingId: Long, locationKey: String, status: Int, body: String = """{"status":$status}""") {
    stubFor(
      put(urlPathEqualTo("/api/bookings/$bookingId/living-unit/$locationKey")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(body)
          .withStatus(status),
      ),
    )
  }

  /**
   * The cell swap. Since MAPA-316 this is the ordinary cell move endpoint with the prison's CSWAP
   * key, so it is stubbed on the same path as [stubMoveToCell] - the location is in the URL rather
   * than being resolved by prison-api from the booking's agency.
   */
  fun stubMoveToCellSwap(
    bookingId: Long,
    locationKey: String = "MDI-CSWAP",
    assignedLivingUnitDesc: String = "MDI-CSWAP",
    bedAssignmentHistorySequence: Int? = 3,
  ) {
    stubFor(
      put(urlPathEqualTo("/api/bookings/$bookingId/living-unit/$locationKey")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "bookingId": $bookingId,
                "agencyId": "MDI",
                "assignedLivingUnitId": 99999,
                "assignedLivingUnitDesc": "$assignedLivingUnitDesc",
                "bedAssignmentHistorySequence": $bedAssignmentHistorySequence
              }
            """.trimIndent(),
          )
          .withStatus(200),
      ),
    )
  }

  /** e.g. 404 when the prison has no CSWAP location configured, or it is described unexpectedly. */
  fun stubMoveToCellSwapFails(
    bookingId: Long,
    status: Int,
    locationKey: String = "MDI-CSWAP",
    body: String = """{"status":$status}""",
  ) {
    stubFor(
      put(urlPathEqualTo("/api/bookings/$bookingId/living-unit/$locationKey")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(body)
          .withStatus(status),
      ),
    )
  }

  /** Any authenticated GET, used to make the client fetch a token so the token request can be asserted. */
  fun stubAnyGet(path: String) {
    stubFor(
      get(path).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{}""")
          .withStatus(200),
      ),
    )
  }
}
