package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId

@Repository
interface CellMovementNomisRepository : JpaRepository<CellMovementNomisEntity, CellMovementNomisId> {

  /**
   * One keyset batch of rows still awaiting enrichment, strictly after the cursor in primary key
   * order. Native because JPQL has no row-value comparison. A full scan behind the LIMIT is
   * acceptable for a one-off migration; no index is added for it.
   */
  @Query(
    value = """
      SELECT * FROM cell_movement_nomis
      WHERE enriched_at IS NULL
        AND (booking_id, bed_assignment_sequence) > (:lastBookingId, :lastBedAssignmentSequence)
      ORDER BY booking_id, bed_assignment_sequence
      LIMIT :batchSize
    """,
    nativeQuery = true,
  )
  fun findUnenrichedAfter(lastBookingId: Long, lastBedAssignmentSequence: Int, batchSize: Int): List<CellMovementNomisEntity>

  // Reconciliation counts. Together with count() they bucket every row exactly once:
  // enriched-with-note + enriched-note-gone + unenriched == total.
  fun countByEnrichedAtIsNotNullAndCaseNoteUuidIsNotNull(): Long

  fun countByEnrichedAtIsNotNullAndCaseNoteUuidIsNull(): Long

  fun countByEnrichedAtIsNull(): Long

  /** The rows no source could put a prisoner number to yet - the backfill's unresolved list. */
  fun countByEnrichedAtIsNullAndPrisonerNumberIsNull(): Long

  fun findTop50ByEnrichedAtIsNullAndPrisonerNumberIsNullOrderByBookingIdAscBedAssignmentSequenceAsc(): List<CellMovementNomisEntity>
}
