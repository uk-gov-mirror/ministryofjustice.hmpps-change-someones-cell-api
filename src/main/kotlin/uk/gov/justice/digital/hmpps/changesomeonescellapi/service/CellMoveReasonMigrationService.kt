package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.EnrichResult
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.MigrationCursor
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.MigrationStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository

/**
 * The one-off backfill (MAPA-304): enriches the rows of
 * [cell_movement_nomis][CellMovementNomisEntity] from their case notes, so that no read ever
 * touches whereabouts again and its table can be dropped.
 *
 * The link sweep that populated the table from whereabouts-api's CELL_MOVE_REASON export is done -
 * it completed and reconciled against whereabouts' own `count(*)` in every environment, and was
 * removed with the export it read (MAPA-282). Enrichment never used whereabouts: it resolves each
 * row's case note through the `case_note_legacy_id` the sweep already copied across.
 *
 * Driven chunk by chunk from MigrationResource by an operator - the curl loop is the scheduler and
 * the rate limiter, which is also why everything here runs synchronously on the request thread (the
 * downstream WebClients are request-scoped and cannot be used from a background job).
 *
 * Not `@Transactional`: each row's write commits alone (via saveAndFlush), so a call cut off by a
 * timeout loses nothing and a re-run from the same cursor is harmless.
 *
 * One race is accepted by design: a row the read path enriches between our select and our save is
 * written twice with the same values - both writers derive the same facts from the same case note,
 * so last-writer-wins is benign.
 */
@Service
class CellMoveReasonMigrationService(
  private val cellMovementNomisRepository: CellMovementNomisRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonApiClient: PrisonApiClient,
  private val enricher: CellMovementNomisEnricher,
) {

  /**
   * Enrich a batch of rows still awaiting enrichment, in primary key order after the
   * cursor. Prisoner numbers are resolved with one batched prisoner-search call, then - only for
   * bookings the index no longer knows - prison-api's booking lookup, the one-off concession the
   * read path deliberately never makes. A booking unknown to both sources is reported in
   * [EnrichResult.unresolvedBookingIds] and its row left untouched: listed, not silent, and
   * retried by any later pass.
   */
  fun enrich(cursor: MigrationCursor, batchSize: Int): EnrichResult {
    val rows = cellMovementNomisRepository.findUnenrichedAfter(
      cursor.lastBookingId,
      cursor.lastBedAssignmentSequence,
      batchSize,
    )
    if (rows.isEmpty()) {
      return EnrichResult(0, 0, 0, 0, emptyList(), cursor, complete = true)
    }

    val searched: Map<Long, String> = prisonerSearchClient
      .getPrisonersByBookingIds(rows.mapTo(mutableSetOf()) { it.bookingId })
      .mapNotNull { p -> p.bookingId?.toLongOrNull()?.let { it to p.prisonerNumber } }
      .toMap()
    // Several sequences can share a booking; ask prison-api about each unknown booking once.
    val fallback = mutableMapOf<Long, String?>()
    fun resolve(bookingId: Long): String? = searched[bookingId]
      ?: fallback.getOrPut(bookingId) { prisonApiClient.getBooking(bookingId)?.offenderNo }

    var enriched = 0
    var noteGone = 0
    var failed = 0
    val unresolved = mutableListOf<Long>()

    rows.forEach { row ->
      val prisonerNumber = row.prisonerNumber ?: resolve(row.bookingId)
      if (prisonerNumber == null) {
        unresolved += row.bookingId
        return@forEach
      }
      try {
        val saved = enricher.enrichWithPrisonerNumber(row, prisonerNumber)
        when {
          saved.enrichedAt == null -> failed++ // transient case-notes failure, already logged
          saved.caseNoteUuid != null -> enriched++
          else -> noteGone++
        }
      } catch (e: Exception) {
        // A pathological row must not abort the batch - count it, log its key, move on.
        failed++
        log.error("Enrichment failed for booking {} sequence {}: {}", row.bookingId, row.bedAssignmentSequence, e.message)
      }
    }

    val last = rows.last()
    log.info(
      "Enrich batch: {} attempted, {} enriched, {} note gone, {} failed, {} unresolved, cursor now ({}, {})",
      rows.size,
      enriched,
      noteGone,
      failed,
      unresolved.distinct().size,
      last.bookingId,
      last.bedAssignmentSequence,
    )

    return EnrichResult(
      attempted = rows.size,
      enriched = enriched,
      noteGone = noteGone,
      failed = failed,
      unresolvedBookingIds = unresolved.distinct(),
      nextCursor = MigrationCursor(last.bookingId, last.bedAssignmentSequence),
      complete = rows.size < batchSize,
    )
  }

  fun status(): MigrationStatus {
    val enrichedWithNote = cellMovementNomisRepository.countByEnrichedAtIsNotNullAndCaseNoteUuidIsNotNull()
    val enrichedNoteGone = cellMovementNomisRepository.countByEnrichedAtIsNotNullAndCaseNoteUuidIsNull()
    return MigrationStatus(
      totalRows = cellMovementNomisRepository.count(),
      enriched = enrichedWithNote + enrichedNoteGone,
      enrichedWithNote = enrichedWithNote,
      enrichedNoteGone = enrichedNoteGone,
      unenriched = cellMovementNomisRepository.countByEnrichedAtIsNull(),
      awaitingPrisonerNumber = cellMovementNomisRepository.countByEnrichedAtIsNullAndPrisonerNumberIsNull(),
      sampleUnresolvedBookingIds = cellMovementNomisRepository
        .findTop50ByEnrichedAtIsNullAndPrisonerNumberIsNullOrderByBookingIdAscBedAssignmentSequenceAsc()
        .map { it.bookingId }
        .distinct(),
    )
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
