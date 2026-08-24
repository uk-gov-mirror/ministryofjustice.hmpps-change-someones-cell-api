package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class WhereaboutsApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val whereaboutsApi = WhereaboutsApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    whereaboutsApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    whereaboutsApi.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    whereaboutsApi.stop()
  }
}

class WhereaboutsApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8094
  }

  /** Note the response is wrapped, and the sequence field carries whereabouts' own misspelling. */
  fun stubGetCellMoveReason(bookingId: Long, bedAssignmentSequence: Int, caseNoteId: Long) {
    stubFor(
      get(urlPathEqualTo("/cell/cell-move-reason/booking/$bookingId/bed-assignment-sequence/$bedAssignmentSequence"))
        .willReturn(
          aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody(
              """
                {
                  "cellMoveReason": {
                    "bookingId": $bookingId,
                    "bedAssignmentsSequence": $bedAssignmentSequence,
                    "caseNoteId": $caseNoteId
                  }
                }
              """.trimIndent(),
            )
            .withStatus(200),
        ),
    )
  }

  fun stubCellMoveReasonNotFound(bookingId: Long, bedAssignmentSequence: Int) {
    stubFor(
      get(urlPathEqualTo("/cell/cell-move-reason/booking/$bookingId/bed-assignment-sequence/$bedAssignmentSequence"))
        .willReturn(
          aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""{"status":404,"developerMessage":"Cell move reason not found"}""")
            .withStatus(404),
        ),
    )
  }

  fun stubCellMoveReasonFails(bookingId: Long, bedAssignmentSequence: Int, status: Int = 500) {
    stubFor(
      get(urlPathEqualTo("/cell/cell-move-reason/booking/$bookingId/bed-assignment-sequence/$bedAssignmentSequence"))
        .willReturn(
          aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""{"status":$status}""")
            .withStatus(status),
        ),
    )
  }
}
