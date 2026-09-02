package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMoveReasonCode
import kotlin.reflect.KClass

/**
 * Asserts a reason code is one this service offers for a *new* move (MAPA-289).
 *
 * Before this existed nothing checked the code at all: an unrecognised one reached prison-api,
 * which rejected the living-unit move with the same undifferentiated 400 it uses for a full or
 * inactive cell, so the user was shown "no space in the cell" for what was really a bad reason
 * code. Rejecting it here makes the failure say what actually happened.
 *
 * Retired codes are rejected as well as unknown ones. Both downstream services would accept them -
 * neither checks `active` - so this is our own tightening, and the right one: the pickers only
 * ever offer active codes, so accepting a retired one would record a move under a reason the
 * estate has withdrawn. The rule stays simple: what you may post is exactly what
 * `GET /cell-movements/reasons` marks active.
 *
 * Deliberately applied to **one field only**, `CellMovementRequest.reasonCode`. Historic codes must
 * keep flowing everywhere else:
 *  - the whereabouts backfill wrote `reasonCode` straight onto the migrated rows, never through a
 *    request DTO, so a migrated 2019 move carrying a retired code is untouched by this;
 *  - the read DTOs keep `reasonCode` as a `String`, so serving a retired code cannot fail;
 *  - there is no database constraint, which would have applied retroactively to migrated rows.
 *
 * The cell swap path is exempt by construction rather than by exception: `CellSwapRequest` has no
 * reason code field, and the swap's hardcoded `ADM` - which prison-api marks *inactive* - never
 * passes through here.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ValidCellMoveReasonValidator::class])
annotation class ValidCellMoveReason(
  val message: String = "reasonCode must be a reason offered by GET /cell-movements/reasons",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
)

class ValidCellMoveReasonValidator : ConstraintValidator<ValidCellMoveReason, String> {
  /**
   * A null or blank value is left to `@NotBlank`, so a missing reason reports "must not be blank"
   * rather than the less helpful "not a recognised reason".
   */
  override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
    if (value.isNullOrBlank()) return true
    return CellMoveReasonCode.isSelectable(value)
  }
}
