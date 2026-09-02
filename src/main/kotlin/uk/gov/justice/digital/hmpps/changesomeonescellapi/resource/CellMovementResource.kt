package uk.gov.justice.digital.hmpps.changesomeonescellapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.Roles
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovement
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementReason
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellSwapRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.service.CellMovementReasonService
import uk.gov.justice.digital.hmpps.changesomeonescellapi.service.CellMovementService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

/**
 * Roles are declared per method rather than on the class. Reading a movement and recording one are
 * separately granted - hmpps-prisoner-profile only ever reads - and a class-level default that each
 * read then had to override would be easy to misread as applying to everything below it.
 * ResourceSecurityTest fails the build if a method is added without one.
 */
@RestController
@Validated
@RequestMapping("/cell-movements", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Cell movements", description = "Records prisoners being moved between cells")
class CellMovementResource(
  private val cellMovementService: CellMovementService,
  private val cellMovementReasonService: CellMovementReasonService,
) {

  @PostMapping
  @PreAuthorize("hasRole('${Roles.CELL_MOVEMENTS_RW}')")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Move a prisoner to a different cell",
    description = "Performs the move in NOMIS, records a MOVED_CELL case note explaining it, and stores our own " +
      "record of the movement. The prisoner's current booking and cell are resolved from prisoner-search, so no " +
      "booking id is needed from the caller. " +
      "If the case note cannot be created the move still succeeds and the movement is returned with status " +
      "CASE_NOTE_FAILED - the explanation is stored, so the case note can be recreated later. " +
      "Requires role ROLE_CELL_MOVEMENTS__RW",
    responses = [
      ApiResponse(
        responseCode = "201",
        description = "The prisoner was moved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CellMovement::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request - including a reasonCode this service does not offer for a new " +
          "move, which is rejected here rather than by NOMIS - or the cell cannot be used because it is " +
          "full, inactive, not a cell or reception, or in a different prison",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such prisoner, or they are not currently in a prison and cannot be moved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "This prisoner was moved to this same cell moments ago - a probable double submission",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "423",
        description = "The prisoner's record is locked, usually because someone has them open in P-NOMIS",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun moveToCell(
    @RequestBody @Valid
    request: CellMovementRequest,
  ): CellMovement = cellMovementService.move(request)

  @PostMapping("/cell-swap")
  @PreAuthorize("hasRole('${Roles.CELL_MOVEMENTS_RW}')")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Move a prisoner out of their cell to free it",
    description = "Performs a cell swap in NOMIS, moving the prisoner to the prison's C-SWAP location so their " +
      "cell can be used by someone else, and records the movement. The destination is the prisoner's own " +
      "prison's cell swap location, so no location is supplied. " +
      "Unlike a cell move this creates **no case note**: the journey does not ask the user why, so there is no " +
      "explanation to record. " +
      "Requires role ROLE_CELL_MOVEMENTS__RW",
    responses = [
      ApiResponse(
        responseCode = "201",
        description = "The prisoner was moved out of their cell",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CellMovement::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, or this prison has no cell swap location configured",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such prisoner, or they are not currently in a prison and cannot be moved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "This prisoner was moved out of their cell moments ago - a probable double submission",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "423",
        description = "The prisoner's record is locked, usually because someone has them open in P-NOMIS. " +
          "Documented for completeness, but NOMIS does not currently return it on the cell swap path.",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun moveToCellSwap(
    @RequestBody @Valid
    request: CellSwapRequest,
  ): CellMovement = cellMovementService.swap(request)

  @GetMapping("/{bookingId}/bed-assignment/{bedAssignmentSequence}")
  @PreAuthorize("hasRole('${Roles.CELL_MOVEMENTS_RO}')")
  @Operation(
    summary = "Get why a prisoner was moved into a cell",
    description = "Returns the reason and the explanation recorded against a NOMIS bed assignment - the " +
      "\"what happened\" text on a prisoner's location history. " +
      "Replaces the two hops this needed before: whereabouts-api for a case note id, then " +
      "offender-case-notes for its text. For a movement this service recorded the explanation is held " +
      "here, so it is answered in one call with nothing downstream. " +
      "Movements migrated from whereabouts were resolved by the completed backfill - explanation, " +
      "reason code and timestamp from the case note whereabouts recorded - and are served from this " +
      "service alone as well. " +
      "Every movement whereabouts ever held has been migrated - the sweep reconciled against its own " +
      "count - so a bed assignment found here is answered from this service alone and one that is not " +
      "is a genuine 404. Fields that cannot be resolved are null - check " +
      "`source` rather than inferring from which fields are null. " +
      "Keyed by booking id because that is how NOMIS keys a bed assignment and how the migrated data " +
      "was keyed - it is not a booking id this service would otherwise accept, and this is the one " +
      "endpoint that accepts one. It is transitional: once consumers render history from this " +
      "service's own records, this lookup retires with it. " +
      "Requires role ROLE_CELL_MOVEMENTS__RO",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The movement recorded against this bed assignment",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CellMovementReason::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__RO role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No cell movement is recorded against this bed assignment. Expected rather than " +
          "exceptional - most bed assignments in NOMIS were never made through DPS - and is what " +
          "whereabouts returned in the same case.",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getCellMovementReason(
    @Parameter(description = "The NOMIS booking id", example = "1200866")
    @PathVariable bookingId: Long,
    @Parameter(description = "The NOMIS bed assignment sequence within that booking", example = "3")
    @PathVariable bedAssignmentSequence: Int,
  ): CellMovementReason = cellMovementReasonService.findByBedAssignment(bookingId, bedAssignmentSequence)
}
