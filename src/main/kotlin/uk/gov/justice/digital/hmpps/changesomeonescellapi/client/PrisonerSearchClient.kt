package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Resolves a prisoner number to their current booking and cell.
 *
 * prisoner-search is the go-to source for prisoner data across HMPPS. It is a near real-time
 * projection of NOMIS fed by events rather than a live read, which is fine for our purposes: we
 * use it to find the booking id and the cell they are leaving, and NOMIS itself validates the
 * real state when the move is performed. Never read it back to confirm a move landed - it will
 * usually still show the old cell.
 */
@Component
class PrisonerSearchClient(
  @param:Qualifier("prisonerSearchWebClient") private val webClient: WebClient,
) {
  /** Returns null when prisoner-search has no record of this prisoner number. */
  fun getPrisoner(prisonerNumber: String): PrisonerSearchPrisoner? = try {
    webClient
      .get()
      .uri("/prisoner/{prisonerNumber}", mapOf("prisonerNumber" to prisonerNumber))
      .retrieve()
      .bodyToMono<PrisonerSearchPrisoner>()
      .block()
  } catch (e: WebClientResponseException) {
    if (e.statusCode == HttpStatus.NOT_FOUND) null else throw e
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonerSearchPrisoner(
  val prisonerNumber: String,
  /** A string here even though NOMIS holds a number, because prisoner-search indexes it as a keyword. */
  val bookingId: String? = null,
  /** The current prison, or OUT / TRN when the prisoner is not inside one. */
  val prisonId: String? = null,
  /**
   * The cell as a path hierarchy with NO prison prefix, e.g. "1-1-015". The location key is
   * "$prisonId-$cellLocation" - see [locationKey].
   */
  val cellLocation: String? = null,
  val inOutStatus: String? = null,
  val status: String? = null,
) {
  /** True only when the prisoner is inside a real prison and can therefore be moved. */
  fun isInPrison(): Boolean = inOutStatus == "IN" && prisonId != null && prisonId !in setOf("OUT", "TRN")

  /** The LIP/NOMIS location key for the cell they are currently in, or null if it cannot be built. */
  fun locationKey(): String? = if (prisonId != null && cellLocation != null) "$prisonId-$cellLocation" else null

  /**
   * The key of this prison's cell swap location. CSWAP is a real location in NOMIS and LIP, one per
   * prison, keyed the same way as any other. Null only when the prisoner is not in a prison, which
   * [isInPrison] already rules out before a swap.
   */
  fun cellSwapLocationKey(): String? = prisonId?.let { "$it-$CELL_SWAP_LOCATION_CODE" }

  private companion object {
    const val CELL_SWAP_LOCATION_CODE = "CSWAP"
  }
}
