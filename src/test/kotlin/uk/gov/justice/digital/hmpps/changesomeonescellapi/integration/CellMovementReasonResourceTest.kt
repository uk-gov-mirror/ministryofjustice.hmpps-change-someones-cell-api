package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiExtension.Companion.caseNotesApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiMockServer.Companion.CASE_NOTE_UUID
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementType
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import java.time.LocalDateTime
import java.util.UUID

/**
 * The "what happened" read (MAPA-279), across both the movements this service records and the ones
 * migrated from whereabouts-api's CELL_MOVE_REASON.
 */
class CellMovementReasonResourceTest : IntegrationTestBase() {

  @Autowired
  private lateinit var cellMovementRepository: CellMovementRepository

  @Autowired
  private lateinit var cellMovementNomisRepository: CellMovementNomisRepository

  private val readRole = listOf("ROLE_CELL_MOVEMENTS__RO")

  @BeforeEach
  fun setUp() {
    cellMovementRepository.deleteAll()
    cellMovementNomisRepository.deleteAll()
    hmppsAuth.stubGrantToken()
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri(uri())
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 without the read role`() {
    webTestClient.get().uri(uri())
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns 404 when nothing is recorded against this bed assignment`() {
    // The common case rather than an edge one: most bed assignments in NOMIS were never made
    // through DPS at all. Whereabouts returned 404 here too, and prisoner-profile tolerates it.
    // Now that the read-through has gone (MAPA-282) this is decided entirely on our own tables.
    get()
      .expectStatus().isNotFound
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CellMovementReasonNotFound")
  }

  @Test
  fun `returns 404 when the booking matches but the sequence does not`() {
    cellMovementRepository.save(aCellMove(bedAssignmentSequence = 99))

    get()
      .expectStatus().isNotFound
  }

  @Test
  fun `serves a movement this service recorded, in one call`() {
    cellMovementRepository.save(aCellMove())

    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.bookingId").isEqualTo(BOOKING_ID)
      .jsonPath("$.bedAssignmentSequence").isEqualTo(BED_ASSIGNMENT_SEQUENCE)
      .jsonPath("$.source").isEqualTo("CELL_MOVEMENTS")
      .jsonPath("$.prisonerNumber").isEqualTo(PRISONER_NUMBER)
      .jsonPath("$.reasonCode").isEqualTo("ADM")
      .jsonPath("$.commentText").isEqualTo(COMMENT)
      .jsonPath("$.caseNoteUuid").isEqualTo(CASE_NOTE_UUID)
      .jsonPath("$.recordedBy").isEqualTo("TEST_USER")
      .jsonPath("$.movementType").isEqualTo("CELL_MOVE")

    // The acceptance criterion that justifies storing comment_text at all: the explanation comes
    // off our own row, with no second hop to offender-case-notes.
    caseNotesApi.verify(0, getRequestedFor(anyUrl()))
    prisonerSearch.verify(0, anyRequestedFor(anyUrl()))
  }

  @Test
  fun `serves a cell swap, which has no explanation to give`() {
    cellMovementRepository.save(
      aCellMove(
        movementType = CellMovementType.CELL_SWAP,
        commentText = null,
        caseNoteUuid = null,
        toLocationKey = "MDI-CSWAP",
      ),
    )

    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.movementType").isEqualTo("CELL_SWAP")
      .jsonPath("$.commentText").doesNotExist()
      .jsonPath("$.caseNoteUuid").doesNotExist()
      // Still a real reason code - ADM is what NOMIS records for every swap.
      .jsonPath("$.reasonCode").isEqualTo("ADM")
  }

  @Test
  fun `prefers the completed movement when a failed attempt left a row behind`() {
    // A move that NOMIS rejected leaves its row PENDING; the retry that worked is the one that
    // describes what happened, and is the later of the two.
    cellMovementRepository.save(
      aCellMove(
        commentText = "First attempt",
        status = CellMovementStatus.PENDING,
        occurredAt = NOW.minusMinutes(5),
      ),
    )
    cellMovementRepository.save(aCellMove(commentText = "Second attempt", occurredAt = NOW))

    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.commentText").isEqualTo("Second attempt")
  }

  @Test
  fun `serves a migrated movement from the row alone`() {
    // What the backfill left behind: everything resolved from the case note, kept on the row.
    cellMovementNomisRepository.save(
      aMigratedMove(
        prisonerNumber = PRISONER_NUMBER,
        reasonCode = "BEH",
        commentText = COMMENT,
        caseNoteUuid = UUID.fromString(CASE_NOTE_UUID),
        occurredAt = LocalDateTime.of(2026, 8, 1, 9, 55),
        enrichedAt = NOW,
      ),
    )

    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.source").isEqualTo("MIGRATED_FROM_WHEREABOUTS")
      .jsonPath("$.bookingId").isEqualTo(BOOKING_ID)
      .jsonPath("$.bedAssignmentSequence").isEqualTo(BED_ASSIGNMENT_SEQUENCE)
      .jsonPath("$.prisonerNumber").isEqualTo(PRISONER_NUMBER)
      .jsonPath("$.reasonCode").isEqualTo("BEH")
      .jsonPath("$.commentText").isEqualTo(COMMENT)
      .jsonPath("$.caseNoteUuid").isEqualTo(CASE_NOTE_UUID)
      .jsonPath("$.caseNoteLegacyId").isEqualTo(LEGACY_CASE_NOTE_ID)
      .jsonPath("$.occurredAt").isEqualTo("2026-08-01T09:55:00")
      // Never recorded anywhere. Asserted so that a later change cannot start inventing them.
      .jsonPath("$.recordedBy").doesNotExist()
      .jsonPath("$.movementType").doesNotExist()

    // Nothing is resolved at read time any more - the migration is complete and nothing downstream
    // is consulted for a migrated movement.
    caseNotesApi.verify(0, anyRequestedFor(anyUrl()))
    prisonerSearch.verify(0, anyRequestedFor(anyUrl()))
  }

  @Test
  fun `serves an unresolved migrated movement with what it has`() {
    // The handful of rows the backfill could not resolve: bookings no source knows. The link
    // survives, so a caller can still read the case note itself, and nothing is fetched on read.
    cellMovementNomisRepository.save(aMigratedMove())

    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.source").isEqualTo("MIGRATED_FROM_WHEREABOUTS")
      .jsonPath("$.bookingId").isEqualTo(BOOKING_ID)
      .jsonPath("$.caseNoteLegacyId").isEqualTo(LEGACY_CASE_NOTE_ID)
      .jsonPath("$.prisonerNumber").doesNotExist()
      .jsonPath("$.reasonCode").doesNotExist()
      .jsonPath("$.commentText").doesNotExist()

    caseNotesApi.verify(0, anyRequestedFor(anyUrl()))
    prisonerSearch.verify(0, anyRequestedFor(anyUrl()))
  }

  @Test
  fun `prefers our own record over a migrated one for the same bed assignment`() {
    // Should not arise - the migration predates anything this service recorded - but if the two
    // ever overlap, ours is the one that holds the explanation rather than pointing at it.
    cellMovementRepository.save(aCellMove())
    cellMovementNomisRepository.save(aMigratedMove())

    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.source").isEqualTo("CELL_MOVEMENTS")
      .jsonPath("$.commentText").isEqualTo(COMMENT)

    caseNotesApi.verify(0, getRequestedFor(anyUrl()))
  }

  private fun get(bookingId: Long = BOOKING_ID, sequence: Int = BED_ASSIGNMENT_SEQUENCE) = webTestClient.get()
    .uri(uri(bookingId, sequence))
    .headers(setAuthorisation(roles = readRole))
    .exchange()

  private fun uri(bookingId: Long = BOOKING_ID, sequence: Int = BED_ASSIGNMENT_SEQUENCE) = "/cell-movements/$bookingId/bed-assignment/$sequence"

  private fun aCellMove(
    bedAssignmentSequence: Int? = BED_ASSIGNMENT_SEQUENCE,
    movementType: CellMovementType = CellMovementType.CELL_MOVE,
    commentText: String? = COMMENT,
    caseNoteUuid: UUID? = UUID.fromString(CASE_NOTE_UUID),
    toLocationKey: String = "MDI-1-1-015",
    status: CellMovementStatus = CellMovementStatus.COMPLETED,
    occurredAt: LocalDateTime = NOW,
  ) = CellMovementEntity(
    prisonerNumber = PRISONER_NUMBER,
    bookingId = BOOKING_ID,
    bedAssignmentSequence = bedAssignmentSequence,
    fromLocationKey = "MDI-1-1-001",
    toLocationKey = toLocationKey,
    reasonCode = "ADM",
    commentText = commentText,
    caseNoteUuid = caseNoteUuid,
    occurredAt = occurredAt,
    recordedBy = "TEST_USER",
    status = status,
    movementType = movementType,
  )

  private fun aMigratedMove(
    prisonerNumber: String? = null,
    reasonCode: String? = null,
    commentText: String? = null,
    caseNoteUuid: UUID? = null,
    occurredAt: LocalDateTime? = null,
    enrichedAt: LocalDateTime? = null,
  ) = CellMovementNomisEntity(
    bookingId = BOOKING_ID,
    bedAssignmentSequence = BED_ASSIGNMENT_SEQUENCE,
    caseNoteLegacyId = LEGACY_CASE_NOTE_ID,
    prisonerNumber = prisonerNumber,
    reasonCode = reasonCode,
    commentText = commentText,
    caseNoteUuid = caseNoteUuid,
    occurredAt = occurredAt,
    enrichedAt = enrichedAt,
  )

  private companion object {
    const val PRISONER_NUMBER = "A1234BC"
    const val BOOKING_ID = 1200866L
    const val BED_ASSIGNMENT_SEQUENCE = 3
    const val LEGACY_CASE_NOTE_ID = 1234567L
    const val COMMENT = "Moved following an altercation on the wing"
    val NOW: LocalDateTime = LocalDateTime.now(clock)
  }
}
