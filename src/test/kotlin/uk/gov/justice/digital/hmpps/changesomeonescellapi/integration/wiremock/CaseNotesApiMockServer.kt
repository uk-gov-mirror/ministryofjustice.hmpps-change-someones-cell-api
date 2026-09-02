package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class CaseNotesApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val caseNotesApi = CaseNotesApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    caseNotesApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    caseNotesApi.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    caseNotesApi.stop()
  }
}

class CaseNotesApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8092
    const val CASE_NOTE_UUID = "6bc0e6a9-7e0f-4a4a-9c62-0d0a0b1d1234"
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

  /** Note caseNoteId is the UUID; legacyId is the deprecated numeric id. */
  fun stubCreateCaseNote(prisonerNumber: String, caseNoteId: String = CASE_NOTE_UUID) {
    stubFor(
      post(urlPathEqualTo("/case-notes/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "caseNoteId": "$caseNoteId",
                "legacyId": 1234567,
                "type": "MOVED_CELL",
                "subType": "ADM"
              }
            """.trimIndent(),
          )
          .withStatus(201),
      ),
    )
  }

  /**
   * A failing create. 403 is the realistic case: MOVED_CELL is a sync-to-nomis type, which
   * case-notes refuses to write without a real NOMIS user.
   */
  fun stubCreateCaseNoteFails(prisonerNumber: String, status: Int = 403) {
    stubFor(
      post(urlPathEqualTo("/case-notes/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{"status":$status,"developerMessage":"Unable to author 'sync to nomis' type without a nomis user"}""")
          .withStatus(status),
      ),
    )
  }
}
