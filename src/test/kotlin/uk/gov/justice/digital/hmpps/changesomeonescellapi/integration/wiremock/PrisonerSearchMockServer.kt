package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonerSearchExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonerSearch = PrisonerSearchMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonerSearch.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonerSearch.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonerSearch.stop()
  }
}

class PrisonerSearchMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8093
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
   * Note [cellLocation] is the path hierarchy with no prison prefix, which is what prisoner-search
   * actually returns - the location key is built as "$prisonId-$cellLocation".
   */
  fun stubGetPrisoner(
    prisonerNumber: String,
    bookingId: String = "1200866",
    prisonId: String = "MDI",
    cellLocation: String = "1-1-001",
    inOutStatus: String = "IN",
  ) {
    stubFor(
      get(urlPathEqualTo("/prisoner/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "prisonerNumber": "$prisonerNumber",
                "bookingId": "$bookingId",
                "prisonId": "$prisonId",
                "prisonName": "HMP Moorland",
                "cellLocation": "$cellLocation",
                "inOutStatus": "$inOutStatus",
                "status": "ACTIVE IN"
              }
            """.trimIndent(),
          )
          .withStatus(200),
      ),
    )
  }

  /** A prisoner who has been released - prisonId becomes OUT and they cannot be moved. */
  fun stubGetReleasedPrisoner(prisonerNumber: String) {
    stubFor(
      get(urlPathEqualTo("/prisoner/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "prisonerNumber": "$prisonerNumber",
                "bookingId": "1200866",
                "prisonId": "OUT",
                "inOutStatus": "OUT",
                "status": "INACTIVE OUT",
                "lastPrisonId": "MDI"
              }
            """.trimIndent(),
          )
          .withStatus(200),
      ),
    )
  }

  fun stubPrisonerNotFound(prisonerNumber: String) {
    stubFor(
      get(urlPathEqualTo("/prisoner/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{"status":404,"userMessage":"$prisonerNumber not found"}""")
          .withStatus(404),
      ),
    )
  }
}
