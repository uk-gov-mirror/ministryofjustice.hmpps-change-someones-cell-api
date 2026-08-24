package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiExtension.Companion.caseNotesApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import java.time.LocalDateTime
import java.util.UUID

/**
 * The one-off backfill (MAPA-304): the enrichment pass and the reconciliation counts. The
 * properties that matter most here are the idempotency ones - a re-run must neither duplicate nor
 * clobber - and that the prison-api fallback stays out of the read path.
 */
class MigrationResourceTest : IntegrationTestBase() {

  @Autowired
  private lateinit var cellMovementNomisRepository: CellMovementNomisRepository

  private val syncRole = listOf("ROLE_CELL_MOVEMENTS__SYNC__RW")

  @BeforeEach
  fun setUp() {
    cellMovementNomisRepository.deleteAll()
    hmppsAuth.stubGrantToken()
  }

  // -- security ---------------------------------------------------------------------------------

  @Test
  fun `enrich returns 401 without a token`() {
    webTestClient.post().uri("/migration/cell-move-reasons/enrich")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `enrich returns 403 without the sync role`() {
    webTestClient.post().uri("/migration/cell-move-reasons/enrich")
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RW")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `status returns 401 without a token`() {
    webTestClient.get().uri("/migration/cell-move-reasons/status")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `status returns 403 without the sync role`() {
    webTestClient.get().uri("/migration/cell-move-reasons/status")
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isForbidden
  }

  // -- enrichment -------------------------------------------------------------------------------

  @Test
  fun `enriches a batch with one prisoner-search call`() {
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    cellMovementNomisRepository.save(aLink(200L, 1, 12L))
    prisonerSearch.stubGetPrisonersByBookingIds(100L to "A1111AA", 200L to "B2222BB")
    caseNotesApi.stubGetCaseNote("A1111AA", "11", subType = "BEH", text = "First explanation")
    caseNotesApi.stubGetCaseNote("B2222BB", "12", subType = "ADM", text = "Second explanation")

    enrich()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.attempted").isEqualTo(2)
      .jsonPath("$.enriched").isEqualTo(2)
      .jsonPath("$.noteGone").isEqualTo(0)
      .jsonPath("$.failed").isEqualTo(0)
      .jsonPath("$.unresolvedBookingIds").isEmpty
      .jsonPath("$.complete").isEqualTo(true)

    // Batched, not per-row: the whole batch resolved its prisoner numbers in one call.
    prisonerSearch.verify(1, postRequestedFor(urlPathEqualTo("/prisoner-search/booking-ids")))

    val first = cellMovementNomisRepository.findAll().single { it.bookingId == 100L }
    assertThat(first.prisonerNumber).isEqualTo("A1111AA")
    assertThat(first.reasonCode).isEqualTo("BEH")
    assertThat(first.commentText).isEqualTo("First explanation")
    assertThat(first.occurredAt).isEqualTo(LocalDateTime.parse("2026-08-01T09:55:00"))
    assertThat(first.enrichedAt).isNotNull()
  }

  @Test
  fun `falls back to prison-api for a booking prisoner-search no longer indexes`() {
    // Someone released and recalled since the move: their old booking is gone from the search
    // index, and only NOMIS still knows whose it was. This lookup is the backfill's one-off
    // concession - the read path never makes it.
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    prisonerSearch.stubGetPrisonerByBookingIdNotFound()
    prisonApi.stubGetBooking(100L, "C3333CC")
    caseNotesApi.stubGetCaseNote("C3333CC", "11")

    enrich()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.enriched").isEqualTo(1)
      .jsonPath("$.unresolvedBookingIds").isEmpty

    prisonApi.verify(1, getRequestedFor(urlPathEqualTo("/api/bookings/100")))
    assertThat(cellMovementNomisRepository.findAll().single().prisonerNumber).isEqualTo("C3333CC")
  }

  @Test
  fun `records a deleted case note as enriched with nothing to fetch`() {
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    prisonerSearch.stubGetPrisonersByBookingIds(100L to "A1111AA")
    caseNotesApi.stubGetCaseNoteNotFound("A1111AA", "11")

    enrich()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.enriched").isEqualTo(0)
      .jsonPath("$.noteGone").isEqualTo(1)

    // enrichedAt set with null note fields is the record that the note is definitively gone -
    // no later pass will ask case-notes about it again.
    val row = cellMovementNomisRepository.findAll().single()
    assertThat(row.enrichedAt).isNotNull()
    assertThat(row.commentText).isNull()
    assertThat(row.caseNoteUuid).isNull()
  }

  @Test
  fun `lists a booking no source can resolve instead of hiding it`() {
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    prisonerSearch.stubGetPrisonerByBookingIdNotFound()
    prisonApi.stubGetBookingNotFound(100L)

    enrich()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.attempted").isEqualTo(1)
      .jsonPath("$.enriched").isEqualTo(0)
      .jsonPath("$.unresolvedBookingIds[0]").isEqualTo(100)

    // Untouched and retryable, not silently skipped.
    val row = cellMovementNomisRepository.findAll().single()
    assertThat(row.prisonerNumber).isNull()
    assertThat(row.enrichedAt).isNull()
  }

  @Test
  fun `a transient case-notes failure leaves the row to retry`() {
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    prisonerSearch.stubGetPrisonersByBookingIds(100L to "A1111AA")
    caseNotesApi.stubGetCaseNoteFails("A1111AA", "11")

    enrich()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.failed").isEqualTo(1)
      .jsonPath("$.enriched").isEqualTo(0)

    // The prisoner number we did learn is kept; enrichedAt stays null so a re-run retries the note.
    val row = cellMovementNomisRepository.findAll().single()
    assertThat(row.prisonerNumber).isEqualTo("A1111AA")
    assertThat(row.enrichedAt).isNull()
  }

  @Test
  fun `re-running enrich touches nothing already done`() {
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    prisonerSearch.stubGetPrisonersByBookingIds(100L to "A1111AA")
    caseNotesApi.stubGetCaseNote("A1111AA", "11")

    enrich().expectStatus().isOk

    enrich()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.attempted").isEqualTo(0)
      .jsonPath("$.complete").isEqualTo(true)

    // One case-notes read in total: the second pass found nothing left to do.
    caseNotesApi.verify(1, getRequestedFor(anyUrl()))
  }

  @Test
  fun `reports complete only when a batch comes back short`() {
    (1..3).forEach { cellMovementNomisRepository.save(aLink(100L * it, 1, 10L + it)) }
    prisonerSearch.stubGetPrisonersByBookingIds(100L to "A1111AA", 200L to "B2222BB", 300L to "C3333CC")
    caseNotesApi.stubGetCaseNote("A1111AA", "11")
    caseNotesApi.stubGetCaseNote("B2222BB", "12")
    caseNotesApi.stubGetCaseNote("C3333CC", "13")

    enrich(batchSize = 2)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.attempted").isEqualTo(2)
      .jsonPath("$.complete").isEqualTo(false)
      .jsonPath("$.nextCursor.lastBookingId").isEqualTo(200)

    enrich(batchSize = 2, lastBookingId = 200, lastBedAssignmentSequence = 1)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.attempted").isEqualTo(1)
      .jsonPath("$.complete").isEqualTo(true)

    assertThat(cellMovementNomisRepository.findAll().all { it.enrichedAt != null }).isTrue()
  }

  // -- the fallback must not leak into the read path ---------------------------------------------

  @Test
  fun `the read path never calls prison-api for a booking it cannot resolve`() {
    // The migrate-on-read journey with an unresolvable booking: served, left for the backfill -
    // and crucially without the backfill's prison-api lookup, which is a NOMIS read the serving
    // path must not acquire.
    cellMovementNomisRepository.save(aLink(100L, 1, 11L))
    prisonerSearch.stubGetPrisonerByBookingIdNotFound()
    prisonApi.stubGetBooking(100L, "C3333CC")

    webTestClient.get().uri("/cell-movements/100/bed-assignment/1")
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.prisonerNumber").doesNotExist()

    prisonApi.verify(0, getRequestedFor(urlPathMatching("/api/bookings/.*")))
  }

  // -- status -----------------------------------------------------------------------------------

  @Test
  fun `status reconciles every row into exactly one bucket`() {
    // One of each state: enriched with its note, enriched but the note is gone, awaiting a
    // prisoner number, and transiently failed (number known, note not yet read).
    cellMovementNomisRepository.save(
      aLink(100L, 1, 11L).apply {
        prisonerNumber = "A1111AA"
        commentText = "Resolved"
        caseNoteUuid = UUID.randomUUID()
        enrichedAt = LocalDateTime.parse("2026-08-10T12:00:00")
      },
    )
    cellMovementNomisRepository.save(
      aLink(200L, 1, 12L).apply {
        prisonerNumber = "B2222BB"
        enrichedAt = LocalDateTime.parse("2026-08-10T12:00:00")
      },
    )
    cellMovementNomisRepository.save(aLink(300L, 1, 13L))
    cellMovementNomisRepository.save(aLink(400L, 1, 14L).apply { prisonerNumber = "D4444DD" })

    webTestClient.get().uri("/migration/cell-move-reasons/status")
      .headers(setAuthorisation(roles = syncRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalRows").isEqualTo(4)
      .jsonPath("$.enriched").isEqualTo(2)
      .jsonPath("$.enrichedWithNote").isEqualTo(1)
      .jsonPath("$.enrichedNoteGone").isEqualTo(1)
      .jsonPath("$.unenriched").isEqualTo(2)
      .jsonPath("$.awaitingPrisonerNumber").isEqualTo(1)
      .jsonPath("$.sampleUnresolvedBookingIds[0]").isEqualTo(300)
  }

  // -- helpers ----------------------------------------------------------------------------------

  private fun enrich(
    lastBookingId: Long = 0,
    lastBedAssignmentSequence: Int = 0,
    batchSize: Int = 50,
  ) = webTestClient.post()
    .uri(
      "/migration/cell-move-reasons/enrich?lastBookingId=$lastBookingId" +
        "&lastBedAssignmentSequence=$lastBedAssignmentSequence&batchSize=$batchSize",
    )
    .headers(setAuthorisation(roles = syncRole))
    .exchange()

  private fun aLink(bookingId: Long, bedAssignmentSequence: Int, caseNoteLegacyId: Long) = CellMovementNomisEntity(
    bookingId = bookingId,
    bedAssignmentSequence = bedAssignmentSequence,
    caseNoteLegacyId = caseNoteLegacyId,
  )
}
