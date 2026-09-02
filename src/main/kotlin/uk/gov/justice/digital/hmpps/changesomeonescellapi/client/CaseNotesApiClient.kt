package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDateTime
import java.util.UUID

/** The case note type NOMIS uses for a cell move. Its subType is the cell move reason code. */
private const val MOVED_CELL = "MOVED_CELL"

/**
 * Records the MOVED_CELL case note for a cell move.
 *
 * The case notes service is the source of truth for case notes - fully migrated, with its own
 * database, syncing back to NOMIS asynchronously. We do not write case notes anywhere else.
 *
 * MOVED_CELL is a "sync to nomis" type, which the service refuses to write without a real NOMIS
 * user. It decides who the user is from the `Username` header, falling back to the token subject,
 * then looks that name up in manage-users. We send the header explicitly rather than depending on
 * how HMPPS Auth happens to populate the subject of a client-credentials token.
 */
@Component
class CaseNotesApiClient(
  @param:Qualifier("caseNotesApiWebClient") private val webClient: WebClient,
) {
  fun createCellMoveCaseNote(
    prisonerNumber: String,
    reasonCode: String,
    text: String,
    occurredAt: LocalDateTime,
    username: String,
  ): CaseNote = webClient
    .post()
    .uri("/case-notes/{prisonerNumber}", mapOf("prisonerNumber" to prisonerNumber))
    .header("Username", username)
    .bodyValue(
      CreateCaseNoteRequest(
        type = MOVED_CELL,
        subType = reasonCode,
        text = text,
        occurrenceDateTime = occurredAt,
      ),
    )
    .retrieve()
    .bodyToMono<CaseNote>()
    .block()!!
}

data class CreateCaseNoteRequest(
  val type: String,
  val subType: String,
  val text: String,
  val occurrenceDateTime: LocalDateTime,
  val systemGenerated: Boolean = false,
)

/** The create response. Only the id is used: it is stored on the movement as its link to the note. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseNote(
  /** Despite the name this is the UUID; the deprecated numeric `legacyId` is not read. */
  val caseNoteId: UUID,
)
