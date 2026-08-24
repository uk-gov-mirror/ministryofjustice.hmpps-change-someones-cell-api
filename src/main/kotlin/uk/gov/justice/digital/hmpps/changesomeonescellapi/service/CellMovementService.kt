package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CaseNotesApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CellMoveResult
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.LocationsInsidePrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchPrisoner
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.DuplicateCellMovementException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.PrisonerNotFoundException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.PrisonerNotInPrisonException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMoveReasonCode
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovement
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellSwapRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementType
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * Orchestrates a cell move: the NOMIS move, the MOVED_CELL case note, and our own record of why it
 * happened. Replaces whereabouts-api's CellMoveService.
 *
 * Deliberately **not** `@Transactional`. Whereabouts wrapped the whole thing in a transaction, but
 * two of the three steps are remote HTTP calls that a rollback cannot undo - so all the annotation
 * ever achieved was discarding the local record of a move that had already happened in NOMIS.
 * Instead each step commits as it completes, so whatever did happen is on record.
 *
 * The ordering is what makes the comment survivable. It is written before the move is attempted,
 * so a failure anywhere downstream still leaves the text we need to recreate the case note.
 */
@Service
class CellMovementService(
  private val cellMovementRepository: CellMovementRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonApiClient: PrisonApiClient,
  private val caseNotesApiClient: CaseNotesApiClient,
  private val locationsInsidePrisonApiClient: LocationsInsidePrisonApiClient,
  private val authenticationHolder: HmppsAuthenticationHolder,
  private val clock: Clock,
) {
  private val username: String get() = authenticationHolder.username ?: SYSTEM_USERNAME

  fun move(request: CellMovementRequest): CellMovement {
    val occurredAt = LocalDateTime.now(clock)
    val prisoner = resolvePrisoner(request.prisonerNumber)

    val movement = recordPending(
      prisoner = prisoner,
      movementType = CellMovementType.CELL_MOVE,
      toLocationKey = request.toLocationKey,
      reasonCode = request.reasonCode,
      commentText = request.commentText,
      occurredAt = occurredAt,
    )

    performInNomis(movement) {
      prisonApiClient.moveToCell(
        bookingId = movement.bookingId,
        locationKey = request.toLocationKey,
        reasonCode = request.reasonCode,
      )
    }

    createCaseNote(movement)

    return movement.toDto()
  }

  /**
   * Moves the prisoner out to their prison's cell swap location, freeing the cell.
   *
   * Records no comment and creates **no case note**: the journey does not ask the user for an
   * explanation, so there is none, and inventing one would put fabricated text on a prisoner's
   * record. The row still captures who did it, when, and which cell it freed.
   */
  fun swap(request: CellSwapRequest): CellMovement {
    val occurredAt = LocalDateTime.now(clock)
    val prisoner = resolvePrisoner(request.prisonerNumber)

    // isInPrison() has already guaranteed a real prisonId, so this cannot be null here.
    val destination = prisoner.cellSwapLocationKey()!!

    val movement = recordPending(
      prisoner = prisoner,
      movementType = CellMovementType.CELL_SWAP,
      toLocationKey = destination,
      // Not asked of the user. ADM is what NOMIS already records for every swap, so sending and
      // storing it keeps our record and the bed assignment in step.
      reasonCode = CELL_SWAP_REASON_CODE,
      commentText = null,
      occurredAt = occurredAt,
    )

    val result = performInNomis(movement) {
      prisonApiClient.moveToCellSwap(
        bookingId = movement.bookingId,
        locationKey = destination,
        reasonCode = CELL_SWAP_REASON_CODE,
      )
    }

    // Prefer the key prison-api reports over the one we derived, and re-resolve the UUID so it
    // matches what is actually stored. Since MAPA-316 we send the key rather than letting prison-api
    // resolve CSWAP from the booking's agency, so the two should now always agree - this stays as
    // the cheap guard that keeps our record honest if they ever do not.
    result.assignedLivingUnitDesc?.takeIf { it != movement.toLocationKey }?.let { actualKey ->
      movement.toLocationKey = actualKey
      movement.toLocationId = locationsInsidePrisonApiClient.resolveKeys(setOf(actualKey))[actualKey]
      cellMovementRepository.saveAndFlush(movement)
    }

    return movement.toDto()
  }

  private fun resolvePrisoner(prisonerNumber: String): PrisonerSearchPrisoner {
    val prisoner = prisonerSearchClient.getPrisoner(prisonerNumber)
      ?: throw PrisonerNotFoundException(prisonerNumber)

    // A prisoner cannot be inside without a booking, so this only fires for someone released or in
    // transit - who cannot be moved anyway. Rejecting here beats letting prison-api fail on a
    // booking id we invented.
    if (!prisoner.isInPrison() || prisoner.bookingId == null) {
      throw PrisonerNotInPrisonException(prisonerNumber)
    }
    return prisoner
  }

  private fun recordPending(
    prisoner: PrisonerSearchPrisoner,
    movementType: CellMovementType,
    toLocationKey: String,
    reasonCode: String,
    commentText: String?,
    occurredAt: LocalDateTime,
  ): CellMovementEntity {
    rejectDuplicate(prisoner.prisonerNumber, toLocationKey, occurredAt)

    // The keys are mutable - codes and hierarchy get renamed in locations-inside-prison - so the
    // UUIDs are the durable identity of the two locations, resolved at the moment of the move. One
    // bulk call covers both. Best effort: an unresolved key stores a null UUID and the move goes
    // ahead regardless.
    val fromLocationKey = prisoner.locationKey()
    val locationIds = locationsInsidePrisonApiClient.resolveKeys(setOfNotNull(fromLocationKey, toLocationKey))

    return cellMovementRepository.saveAndFlush(
      CellMovementEntity(
        prisonerNumber = prisoner.prisonerNumber,
        bookingId = prisoner.bookingId!!.toLong(),
        fromLocationKey = fromLocationKey,
        fromLocationId = fromLocationKey?.let { locationIds[it] },
        toLocationId = locationIds[toLocationKey],
        toLocationKey = toLocationKey,
        reasonCode = reasonCode,
        commentText = commentText,
        occurredAt = occurredAt,
        recordedBy = username,
        status = CellMovementStatus.PENDING,
        movementType = movementType,
      ),
    )
  }

  private fun performInNomis(movement: CellMovementEntity, call: () -> CellMoveResult): CellMoveResult {
    val result = try {
      call()
    } catch (e: Exception) {
      // Leave the row PENDING: it is the record that we tried and NOMIS did not accept it.
      log.warn("Cell movement failed in NOMIS for {}: {}", movement.prisonerNumber, e.message)
      throw e
    }

    // Null when the prisoner was already in that location, which prison-api treats as a successful
    // no-op rather than an error. The movement is still COMPLETED - there is simply no new
    // assignment.
    movement.bedAssignmentSequence = result.bedAssignmentHistorySequence
    movement.status = CellMovementStatus.COMPLETED
    cellMovementRepository.saveAndFlush(movement)
    return result
  }

  private fun rejectDuplicate(prisonerNumber: String, toLocationKey: String, occurredAt: LocalDateTime) {
    val duplicate = cellMovementRepository.existsByPrisonerNumberAndToLocationKeyAndOccurredAtAfter(
      prisonerNumber,
      toLocationKey,
      occurredAt.minus(DUPLICATE_WINDOW),
    )
    if (duplicate) {
      throw DuplicateCellMovementException(prisonerNumber, toLocationKey)
    }
  }

  /**
   * The move has already happened by this point, so a failure here must not fail the request. The
   * comment text is safely stored, so the case note can be recreated later from the row.
   *
   * Only ever called for a [CellMovementType.CELL_MOVE], which always carries a comment - a cell
   * swap has none and gets no case note.
   */
  private fun createCaseNote(movement: CellMovementEntity) {
    try {
      val caseNote = caseNotesApiClient.createCellMoveCaseNote(
        prisonerNumber = movement.prisonerNumber,
        reasonCode = movement.reasonCode,
        text = movement.commentText!!,
        occurredAt = movement.occurredAt,
        username = movement.recordedBy,
      )
      movement.caseNoteUuid = caseNote.caseNoteId
    } catch (e: Exception) {
      log.error("Cell move for {} succeeded but the case note failed", movement.prisonerNumber, e)
      movement.status = CellMovementStatus.CASE_NOTE_FAILED
    }
    cellMovementRepository.saveAndFlush(movement)
  }

  private fun CellMovementEntity.toDto() = CellMovement(
    id = id!!,
    movementType = movementType,
    prisonerNumber = prisonerNumber,
    fromLocationKey = fromLocationKey,
    fromLocationId = fromLocationId,
    toLocationKey = toLocationKey,
    toLocationId = toLocationId,
    reasonCode = reasonCode,
    occurredAt = occurredAt,
    recordedBy = recordedBy,
    caseNoteUuid = caseNoteUuid,
    status = status,
  )

  private companion object {
    // Long enough to catch a double submit, short enough not to block a prisoner legitimately
    // being moved back to a cell later in the day.
    // What NOMIS already records for every cell swap. Ours, not prison-api's default, so the two
    // cannot drift apart. Taken from the enum rather than written as a string so it is a member of
    // the list this service serves by construction. Note ADM is *inactive*: it is not offered for a
    // new move, and could not be posted as one - the swap journey asks the user for no reason at
    // all, so it never passes through reasonCode validation.
    private val CELL_SWAP_REASON_CODE = CellMoveReasonCode.ADM.code
    private val DUPLICATE_WINDOW: Duration = Duration.ofSeconds(60)
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
