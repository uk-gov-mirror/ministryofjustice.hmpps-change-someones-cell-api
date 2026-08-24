package uk.gov.justice.digital.hmpps.changesomeonescellapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.Roles
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.EnrichResult
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.MigrationCursor
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.MigrationStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.service.CellMoveReasonMigrationService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

/**
 * The one-off backfill's control surface (MAPA-304). Operator-driven and chunked: each call does a
 * bounded amount of work synchronously and returns a cursor, and the operator's loop - not a
 * scheduler - drives it to completion. Idempotent throughout, so a timed-out or repeated call
 * costs nothing.
 *
 * The link sweep that drained whereabouts-api's CELL_MOVE_REASON table has completed and
 * reconciled in every environment, and went with the export it read (MAPA-282). What remains is
 * the enrichment pass, which reads case-notes and never touched whereabouts.
 *
 * **Temporary.** These endpoints are deleted once enrichment has run to completion.
 */
@RestController
@Validated
@RequestMapping("/migration/cell-move-reasons", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(
  name = "Migration (temporary)",
  description = "Operator-driven backfill of whereabouts-api's cell move reasons. Dies once enrichment completes.",
)
class MigrationResource(
  private val migrationService: CellMoveReasonMigrationService,
) {

  @PostMapping("/enrich")
  @PreAuthorize("hasRole('${Roles.CELL_MOVEMENTS_SYNC_RW}')")
  @Operation(
    summary = "Enrich a batch of migrated rows from their case notes",
    description = "Takes a batch of rows still awaiting enrichment, in key order after the cursor, " +
      "resolves their prisoner numbers (one batched prisoner-search call, then prison-api's booking " +
      "lookup for bookings the index no longer knows), and resolves each row's case note - reason " +
      "code, explanation, timestamp - onto it, once. Rows enriched by the read path in the meantime " +
      "are not re-read. Bookings no source can resolve are returned in unresolvedBookingIds and " +
      "their rows left for a later pass. The batch runs sequentially on this request's thread, " +
      "which is also the rate limit on case-notes. Loop on nextCursor until complete is true. " +
      "Requires role ROLE_CELL_MOVEMENTS__SYNC__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The batch was processed",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = EnrichResult::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "A parameter is out of bounds",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__SYNC__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun enrich(
    @Parameter(description = "Continue strictly after this booking id", example = "0")
    @RequestParam(defaultValue = "0") lastBookingId: Long,
    @Parameter(description = "Continue strictly after this bed assignment sequence within that booking", example = "0")
    @RequestParam(defaultValue = "0") lastBedAssignmentSequence: Int,
    @Parameter(description = "Rows to enrich in this call, bounding its duration", example = "50")
    @RequestParam(defaultValue = "50")
    @Min(1)
    @Max(100) batchSize: Int,
  ): EnrichResult = migrationService.enrich(
    MigrationCursor(lastBookingId, lastBedAssignmentSequence),
    batchSize,
  )

  @GetMapping("/status")
  @PreAuthorize("hasRole('${Roles.CELL_MOVEMENTS_SYNC_RW}')")
  @Operation(
    summary = "Report migration progress and the reconciliation counts",
    description = "The counts that prove convergence: totalRows, which the completed link sweep " +
      "reconciled against whereabouts' own count(*), the enriched/unenriched split, and a sample of " +
      "booking ids no source could resolve - the " +
      "\"explicitly accounted for\" list to record on the migration ticket. " +
      "Requires role ROLE_CELL_MOVEMENTS__SYNC__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The current counts",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = MigrationStatus::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__SYNC__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun status(): MigrationStatus = migrationService.status()
}
