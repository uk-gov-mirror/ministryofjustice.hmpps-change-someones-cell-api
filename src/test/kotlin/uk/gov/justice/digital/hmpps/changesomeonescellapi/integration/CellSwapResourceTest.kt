package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiExtension.Companion.caseNotesApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonExtension.Companion.locationsInsidePrison
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonMockServer
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementType
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository

class CellSwapResourceTest : IntegrationTestBase() {

  @Autowired
  private lateinit var cellMovementRepository: CellMovementRepository

  private val writeRole = listOf("ROLE_CELL_MOVEMENTS__RW")

  @BeforeEach
  fun setUp() {
    cellMovementRepository.deleteAll()
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner(PRISONER_NUMBER, bookingId = BOOKING_ID.toString(), cellLocation = FROM_CELL)
    prisonApi.stubMoveToCellSwap(BOOKING_ID)
    locationsInsidePrison.stubResolveKeys(
      "MDI-$FROM_CELL" to LocationsInsidePrisonMockServer.FROM_LOCATION_ID,
      "MDI-CSWAP" to CSWAP_LOCATION_ID,
    )
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.post().uri(URI)
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody()))
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 without the write role`() {
    webTestClient.post().uri(URI)
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody()))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `swaps the prisoner out and records the movement`() {
    postSwap()
      .expectStatus().isCreated
      .expectBody()
      .jsonPath("$.prisonerNumber").isEqualTo(PRISONER_NUMBER)
      .jsonPath("$.movementType").isEqualTo("CELL_SWAP")
      .jsonPath("$.fromLocationKey").isEqualTo("MDI-$FROM_CELL")
      .jsonPath("$.toLocationKey").isEqualTo("MDI-CSWAP")
      .jsonPath("$.toLocationId").isEqualTo(CSWAP_LOCATION_ID)
      .jsonPath("$.reasonCode").isEqualTo("ADM")
      .jsonPath("$.status").isEqualTo("COMPLETED")
      .jsonPath("$.caseNoteUuid").doesNotExist()

    val movement = cellMovementRepository.findAll().single()
    assertThat(movement.movementType).isEqualTo(CellMovementType.CELL_SWAP)
    assertThat(movement.status).isEqualTo(CellMovementStatus.COMPLETED)
    assertThat(movement.commentText).isNull()
    assertThat(movement.caseNoteUuid).isNull()
    assertThat(movement.bedAssignmentSequence).isEqualTo(3)
  }

  @Test
  fun `does not create a case note`() {
    postSwap().expectStatus().isCreated

    // The load-bearing assertion for the whole decision: the journey never asks the user why, so
    // there is nothing legitimate to write, and nothing must be invented.
    caseNotesApi.verify(0, postRequestedFor(anyUrl()))
  }

  @Test
  fun `swaps through the ordinary cell move endpoint with the prison's CSWAP key`() {
    postSwap().expectStatus().isCreated

    // MAPA-316: prison-api's move-to-cell-swap is deprecated, and the ordinary cell move now accepts
    // a cell swap destination. We send the key rather than letting prison-api resolve it.
    prisonApi.verify(0, putRequestedFor(urlMatching(".*/move-to-cell-swap.*")))
    val request = prisonApi.findAll(
      putRequestedFor(urlPathEqualTo("/api/bookings/$BOOKING_ID/living-unit/MDI-CSWAP")),
    ).single()
    // Required now this is the ordinary move, where there is no ADM default to fall back on.
    assertThat(request.queryParameter("reasonCode").firstValue()).isEqualTo("ADM")
    // Cell swap gains the lock timeout the old endpoint hardcoded off, so it can now return 423.
    assertThat(request.queryParameter("lockTimeout").firstValue()).isEqualTo("true")
  }

  @Test
  fun `derives the destination from the prisoner's own prison`() {
    prisonerSearch.stubGetPrisoner(PRISONER_NUMBER, bookingId = BOOKING_ID.toString(), prisonId = "LEI")
    prisonApi.stubMoveToCellSwap(BOOKING_ID, locationKey = "LEI-CSWAP", assignedLivingUnitDesc = "LEI-CSWAP")

    postSwap()
      .expectStatus().isCreated
      .expectBody()
      .jsonPath("$.toLocationKey").isEqualTo("LEI-CSWAP")
  }

  @Test
  fun `prefers prison-api's location over the derived one`() {
    // Now that we send the key, prison-api should always echo it back. This is the guard that keeps
    // our record honest if it ever reports something else.
    prisonApi.stubMoveToCellSwap(BOOKING_ID, assignedLivingUnitDesc = "MDI-CSWAP-2")

    postSwap().expectStatus().isCreated

    assertThat(cellMovementRepository.findAll().single().toLocationKey).isEqualTo("MDI-CSWAP-2")
  }

  @Test
  fun `a prisoner already in cell swap is not an error`() {
    prisonApi.stubMoveToCellSwap(BOOKING_ID, bedAssignmentHistorySequence = null)

    postSwap().expectStatus().isCreated

    val movement = cellMovementRepository.findAll().single()
    assertThat(movement.status).isEqualTo(CellMovementStatus.COMPLETED)
    assertThat(movement.bedAssignmentSequence).isNull()
  }

  @Test
  fun `a prison with no usable cell swap location is not reported as cell not available`() {
    prisonApi.stubMoveToCellSwapFails(
      BOOKING_ID,
      status = 404,
      body = """{"status":404,"userMessage":"CSWAP location not found for MDI"}""",
    )

    postSwap()
      .expectStatus().isBadRequest
      .expectBody()
      // There is no destination cell and no capacity check here, so telling the user to pick a
      // different cell would be nonsense.
      .jsonPath("$.errorCode").isEqualTo("CellSwapUnavailable")

    assertThat(cellMovementRepository.findAll().single().status).isEqualTo(CellMovementStatus.PENDING)
  }

  @Test
  fun `a record open in P-NOMIS surfaces as 423`() {
    // Reachable since MAPA-316: the swap goes through the living-unit call with lockTimeout=true,
    // where it used to block on a record open in P-NOMIS rather than returning a clean error.
    prisonApi.stubMoveToCellSwapFails(BOOKING_ID, status = 423)

    postSwap()
      .expectStatus().isEqualTo(423)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("PrisonerRecordLocked")
  }

  @Test
  fun `a repeated submission is rejected with 409`() {
    postSwap().expectStatus().isCreated

    postSwap()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("DuplicateCellMovement")

    assertThat(cellMovementRepository.findAll()).hasSize(1)
  }

  @Test
  fun `an unknown prisoner is rejected with 404`() {
    prisonerSearch.stubPrisonerNotFound(PRISONER_NUMBER)

    postSwap()
      .expectStatus().isNotFound
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("PrisonerNotFound")

    assertThat(cellMovementRepository.findAll()).isEmpty()
  }

  @Test
  fun `a released prisoner cannot be swapped out`() {
    prisonerSearch.stubGetReleasedPrisoner(PRISONER_NUMBER)

    postSwap()
      .expectStatus().isNotFound
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("PrisonerNotInPrison")
  }

  @Test
  fun `rejects a malformed prisoner number`() {
    webTestClient.post().uri(URI)
      .headers(setAuthorisation(username = USER, roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody(prisonerNumber = "NOT-A-NUMBER")))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `re-resolves the UUID when prison-api returns a different location than derived`() {
    prisonApi.stubMoveToCellSwap(BOOKING_ID, assignedLivingUnitDesc = "MDI-CSWAP-2")
    locationsInsidePrison.stubResolveKeys(
      "MDI-CSWAP" to CSWAP_LOCATION_ID,
      "MDI-CSWAP-2" to ACTUAL_CSWAP_LOCATION_ID,
    )

    postSwap().expectStatus().isCreated

    // The stored UUID must identify the location actually stored in the key, not the one we
    // guessed before the call.
    val movement = cellMovementRepository.findAll().single()
    org.assertj.core.api.Assertions.assertThat(movement.toLocationKey).isEqualTo("MDI-CSWAP-2")
    org.assertj.core.api.Assertions.assertThat(movement.toLocationId)
      .isEqualTo(java.util.UUID.fromString(ACTUAL_CSWAP_LOCATION_ID))
  }

  private fun postSwap() = webTestClient.post().uri(URI)
    .headers(setAuthorisation(username = USER, roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(requestBody()))
    .exchange()

  private fun requestBody(prisonerNumber: String = PRISONER_NUMBER) = """{ "prisonerNumber": "$prisonerNumber" }"""

  private companion object {
    const val URI = "/cell-movements/cell-swap"
    const val PRISONER_NUMBER = "A1234BC"
    const val BOOKING_ID = 1200866L
    const val FROM_CELL = "1-1-001"
    const val USER = "TEST_USER"
    const val CSWAP_LOCATION_ID = "7c1e2f3a-1111-4d4d-8888-aaaaaaaaaaaa"
    const val ACTUAL_CSWAP_LOCATION_ID = "7c1e2f3a-2222-4d4d-8888-bbbbbbbbbbbb"
  }
}
