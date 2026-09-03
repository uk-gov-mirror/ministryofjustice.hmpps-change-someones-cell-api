package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

/**
 * A cell move reason inherited from whereabouts-api's CELL_MOVE_REASON table, plus what this
 * service has since resolved about it.
 *
 * The first three fields are the link exactly as whereabouts held it, copied by the one-off sweep.
 * The rest was resolved by the completed backfill from the case note the link points at - the only
 * place the prisoner number, reason code, explanation and timestamp survived, because the source
 * table held none of them. Nothing writes here any more: the table is history, read as it stands.
 *
 * New movements go in [CellMovementEntity]; nothing here is ever a movement this service made.
 */
@Entity
@Table(name = "cell_movement_nomis")
@IdClass(CellMovementNomisId::class)
class CellMovementNomisEntity(

  @Id
  @Column(name = "booking_id", updatable = false, nullable = false)
  val bookingId: Long,

  @Id
  @Column(name = "bed_assignment_sequence", updatable = false, nullable = false)
  val bedAssignmentSequence: Int,

  /**
   * The numeric case note id, copied unchanged from the source. Still resolvable: the case notes
   * service accepts either a UUID or a legacy id on `GET /case-notes/{personIdentifier}/{id}`,
   * even though it treats the numeric form as deprecated.
   */
  @Column(name = "case_note_legacy_id", nullable = false)
  val caseNoteLegacyId: Long,

  var prisonerNumber: String? = null,

  /** The CHG_HOUS_RSN code, from the case note's subType. */
  var reasonCode: String? = null,

  var commentText: String? = null,

  var caseNoteUuid: UUID? = null,

  /** The case note's occurrenceDateTime, which whereabouts set to the moment of the move. */
  var occurredAt: LocalDateTime? = null,

  /**
   * When the backfill resolved this row. Null on the handful of rows no source could put a prisoner
   * number to; set alongside null note fields means the case note was already gone.
   */
  var enrichedAt: LocalDateTime? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CellMovementNomisEntity
    return bookingId == other.bookingId && bedAssignmentSequence == other.bedAssignmentSequence
  }

  override fun hashCode(): Int = 31 * bookingId.hashCode() + bedAssignmentSequence
}

/**
 * The composite key. It is the natural key from the source table, kept rather than replaced with a
 * surrogate id so that re-running the one-off migration cannot duplicate a row.
 */
data class CellMovementNomisId(
  val bookingId: Long = 0,
  val bedAssignmentSequence: Int = 0,
) : Serializable
