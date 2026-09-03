package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellMovementReasonNotFoundException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementReason
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementSource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import kotlin.jvm.optionals.getOrNull

/**
 * Serves "what happened" for a cell move, across the movements this service records and the ones
 * migrated from whereabouts-api. Callers get one endpoint and do not have to know which side a
 * movement came from.
 *
 * The migration is finished on both counts. The link sweep reconciled against whereabouts' own
 * `count(*)` in every environment (3,516,520 rows in production), the backfill then resolved each
 * row's prisoner number, reason code, explanation and timestamp, and whereabouts' table and `/cell`
 * endpoints are gone (MAPA-282, MAPA-304, MAPA-342). So this is a pure read of two tables, with
 * nothing downstream and nothing written: a key missing from both is genuinely not found, and a
 * migrated row is served exactly as it stands. The few rows the backfill could not resolve - bookings
 * no source knows - carry their case note legacy id and nothing else.
 */
@Service
class CellMovementReasonService(
  private val cellMovementRepository: CellMovementRepository,
  private val cellMovementNomisRepository: CellMovementNomisRepository,
) {

  fun findByBedAssignment(bookingId: Long, bedAssignmentSequence: Int): CellMovementReason {
    cellMovementRepository
      .findFirstByBookingIdAndBedAssignmentSequenceOrderByOccurredAtDesc(bookingId, bedAssignmentSequence)
      ?.let { return it.toReason() }

    return cellMovementNomisRepository
      .findById(CellMovementNomisId(bookingId, bedAssignmentSequence))
      .getOrNull()
      ?.toReason()
      ?: throw CellMovementReasonNotFoundException(bookingId, bedAssignmentSequence)
  }

  /** Everything is on the row. No downstream call, whatever the status of the movement. */
  private fun CellMovementEntity.toReason() = CellMovementReason(
    bookingId = bookingId,
    // Not null: this row was found by matching on it.
    bedAssignmentSequence = bedAssignmentSequence!!,
    source = CellMovementSource.CELL_MOVEMENTS,
    toLocationKey = toLocationKey,
    toLocationId = toLocationId,
    fromLocationKey = fromLocationKey,
    fromLocationId = fromLocationId,
    prisonerNumber = prisonerNumber,
    reasonCode = reasonCode,
    commentText = commentText,
    caseNoteUuid = caseNoteUuid,
    caseNoteLegacyId = caseNoteLegacyId,
    occurredAt = occurredAt,
    recordedBy = recordedBy,
    movementType = movementType,
  )

  private fun CellMovementNomisEntity.toReason() = CellMovementReason(
    bookingId = bookingId,
    bedAssignmentSequence = bedAssignmentSequence,
    source = CellMovementSource.MIGRATED_FROM_WHEREABOUTS,
    prisonerNumber = prisonerNumber,
    reasonCode = reasonCode,
    commentText = commentText,
    caseNoteUuid = caseNoteUuid,
    caseNoteLegacyId = caseNoteLegacyId,
    occurredAt = occurredAt,
    // Whereabouts never recorded who performed the move; that fact lives only in NOMIS.
    recordedBy = null,
    // Unknowable. Whereabouts never performed a cell swap, so in practice every migrated row is a
    // cell move - but "in practice" is not something to assert on a prisoner's record.
    movementType = null,
  )
}
