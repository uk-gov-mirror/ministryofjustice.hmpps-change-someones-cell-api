package uk.gov.justice.digital.hmpps.changesomeonescellapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Where an enrichment pass got to. Feed it back as the next call's `lastBookingId` /
 * `lastBedAssignmentSequence` to continue from there; `(0, 0)` starts from the beginning.
 */
@Schema(description = "The keyset cursor a chunked migration call finished at")
data class MigrationCursor(
  @get:Schema(description = "The booking id of the last row processed", example = "1200866")
  val lastBookingId: Long,

  @get:Schema(description = "The bed assignment sequence of the last row processed", example = "3")
  val lastBedAssignmentSequence: Int,
)

@Schema(description = "The result of one chunk of the enrichment pass")
data class EnrichResult(
  @get:Schema(description = "Rows this call attempted")
  val attempted: Int,

  @get:Schema(description = "Rows fully enriched from their case note")
  val enriched: Int,

  @get:Schema(description = "Rows whose case note is definitively gone - enriched with nothing to fetch")
  val noteGone: Int,

  @get:Schema(description = "Rows whose case note read failed transiently - left to retry on a later pass")
  val failed: Int,

  @get:Schema(description = "Booking ids neither prisoner-search nor prison-api could resolve to a prisoner - listed, not hidden; their rows are left untouched")
  val unresolvedBookingIds: List<Long>,

  @get:Schema(description = "Where this call got to - pass back to continue")
  val nextCursor: MigrationCursor,

  @get:Schema(description = "True when no rows past the cursor still await enrichment")
  val complete: Boolean,
)

/**
 * The reconciliation identities: [totalRows] equalled whereabouts' `count(*)` when the link sweep
 * completed; `enrichedWithNote + enrichedNoteGone + unenriched == totalRows`; and
 * `unenriched - awaitingPrisonerNumber` is the transiently-failed remainder a re-run of the
 * enrichment pass will retry.
 */
@Schema(description = "Migration progress and the reconciliation counts")
data class MigrationStatus(
  @get:Schema(description = "All migrated rows")
  val totalRows: Long,

  @get:Schema(description = "Rows enriched, whether or not their case note still existed")
  val enriched: Long,

  @get:Schema(description = "Rows enriched from a case note that still existed")
  val enrichedWithNote: Long,

  @get:Schema(description = "Rows whose case note is definitively gone")
  val enrichedNoteGone: Long,

  @get:Schema(description = "Rows still awaiting enrichment")
  val unenriched: Long,

  @get:Schema(description = "Of the unenriched, rows whose booking no source could resolve to a prisoner")
  val awaitingPrisonerNumber: Long,

  @get:Schema(description = "Up to 50 of the unresolved booking ids, for recording on the migration ticket")
  val sampleUnresolvedBookingIds: List<Long>,
)
