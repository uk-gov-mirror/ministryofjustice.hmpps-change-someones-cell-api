package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellNotAvailableException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellSwapUnavailableException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.PrisonerRecordLockedException

/**
 * Performs the actual cell move in NOMIS.
 *
 * The endpoint is `@ProxyUser`, so the username carried on our token is what NOMIS records in its
 * audit columns - see WebClientConfiguration for how that gets there.
 */
@Component
class PrisonApiClient(
  @param:Qualifier("prisonApiWebClient") private val webClient: WebClient,
) {
  /**
   * Moves the booking to [locationKey], which is the full location key such as MDI-1-1-015 -
   * prison-api matches it against the NOMIS internal location description, which is the same
   * string LIP calls a key.
   */
  fun moveToCell(bookingId: Long, locationKey: String, reasonCode: String): CellMoveResult = putLivingUnit(
    bookingId,
    locationKey,
    reasonCode,
    // The cell is full, inactive, not a cell or reception, or in another prison. prison-api reports
    // all of these as one 400, so its message is passed through rather than guessed at.
    rejectedBy = { CellNotAvailableException(it) },
  )

  /**
   * Moves the booking out to the prison's cell swap location, freeing the cell.
   *
   * This is [moveToCell] with the prison's `PRISON_ID-CSWAP` key, which the caller derives and
   * stores. It exists as its own method only because a failure here means something different -
   * see [rejectedBy] below - and because the caller reads better for it.
   *
   * It used to call prison-api's `move-to-cell-swap`, which took no location and resolved CSWAP
   * from the booking's own agency. That endpoint was deprecated, and MAPA-316 widened the ordinary
   * cell move to accept a cell swap destination so it could go. The one thing that changes with it:
   * prison-api no longer resolves the location for us, so a prison whose CSWAP location is not
   * described `PRISON_ID-CSWAP` now fails here rather than being silently corrected. Every CSWAP
   * location is described that way - it is a top-level location with code CSWAP - but that is a
   * NOMIS convention rather than a guarantee, which is what [CellSwapUnavailableException] is for.
   *
   * [reasonCode] is required now that this is the ordinary cell move; the swap endpoint used to
   * default it to ADM. The caller has always sent it explicitly, so nothing changes.
   */
  fun moveToCellSwap(bookingId: Long, locationKey: String, reasonCode: String): CellMoveResult = putLivingUnit(
    bookingId,
    locationKey,
    reasonCode,
    // Not CellNotAvailable: there is no destination cell here and no capacity check - CSWAP is
    // deliberately uncapped. A 400 or 404 means the prison has no CSWAP location configured, or it
    // is not described the way we derived. That is an estate configuration fault, not something the
    // user can fix by picking a different cell.
    rejectedBy = { CellSwapUnavailableException(it) },
  )

  /**
   * The one call that moves a booking. Shared by the ordinary move and the cell swap, which differ
   * only in the key they send and in what a rejection means.
   *
   * There is deliberately **no retry**. whereabouts-api applied `.retry(3)` here, which retries a
   * non-idempotent write on any error including 4xx, and could move a prisoner more than once.
   *
   * `lockTimeout=true` asks NOMIS to take a row lock and give up after 10 seconds rather than
   * blocking, which is what turns "someone has this prisoner open in P-NOMIS" into a 423 we can
   * show the user.
   */
  private fun putLivingUnit(
    bookingId: Long,
    locationKey: String,
    reasonCode: String,
    rejectedBy: (String?) -> Exception,
  ): CellMoveResult = translatingErrors(rejectedBy) {
    webClient
      .put()
      .uri(
        "/api/bookings/{bookingId}/living-unit/{locationKey}?reasonCode={reasonCode}&lockTimeout=true",
        mapOf("bookingId" to bookingId, "locationKey" to locationKey, "reasonCode" to reasonCode),
      )
      .retrieve()
      .bodyToMono<CellMoveResult>()
      .block()!!
  }

  /**
   * Shared so the two calls cannot drift. Both now go through the same endpoint with
   * `lockTimeout=true`, so a record open in P-NOMIS is a 423 on a swap as well as on an ordinary
   * move - it used to block instead, because the old swap endpoint hardcoded no lock timeout.
   */
  private fun <T> translatingErrors(rejectedBy: (String?) -> Exception, block: () -> T): T = try {
    block()
  } catch (e: WebClientResponseException) {
    when (e.statusCode) {
      HttpStatus.LOCKED -> throw PrisonerRecordLockedException()
      HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND -> throw rejectedBy(e.responseBodyAsString)
      else -> throw e
    }
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CellMoveResult(
  val bookingId: Long,
  val agencyId: String? = null,
  val assignedLivingUnitId: Long? = null,
  val assignedLivingUnitDesc: String? = null,
  /**
   * Null when the prisoner was already in the destination cell - prison-api treats that as a
   * successful no-op rather than an error.
   */
  val bedAssignmentHistorySequence: Int? = null,
)
