// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.scala.core.validations

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Trait for deriving Object Validators classes and objects.
 *
 * object EmployeeValidator extends ObjectValidator[Employee] {
 *   val fieldValidator = { employee =>
 *    Set(
 *     employee.firstName mustBe NonEmptyString,
 *     employee.ssn mustBe ValidUsSSN,
 *     ...)
 *   }
 * }
 *
 * val validationResult = EmployeeValidator(employee)
 *
 * @tparam A The Class of the validatable object
 */
trait ObjectValidator[A] extends Validator[A] {
  def fieldValidator: FieldValidator[A]

  def apply(input: ValidatorInput[A]): ValidationResult[A] = {
    fieldValidator(input._1).flatMap {
      case Left(value) => value
      case Right(_)    => Seq.empty
    } match {
      case errors if errors.isEmpty => Right(input._1)
      case errors                   => Left(errors)
    }
  }
}

/**
 * Factory for Creating Anonymous Object Validators values.
 *
 * val EmployeeValidator = ObjectValidator[Employee] { employee =>
 *    Set(
 *     employee.firstName mustBe NonEmptyString,
 *     employee.ssn mustBe ValidUsSSN,
 *     ...)
 * }
 *
 * use the same way as the Validator Object above
 *
 */
object ObjectValidator {
  def apply[A](validators: FieldValidator[A]): ObjectValidator[A] =
    new Object() with ObjectValidator[A] {
      def fieldValidator: FieldValidator[A] = validators
    }
}

/**
 * Trait for creating Asynchronous Object Validators.
 *
 * Use this in place of ObjectValidator where any of the field validations are asynchronous
 *
 * @tparam A The Class of the validatable objects
 */
trait AsyncObjectValidator[A] extends AsyncValidator[A] {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  def fieldValidator: AsyncFieldValidator[A]

  def apply(input: ValidatorInput[A]): AsyncValidationResult[A] = {
    val validations: Seq[Future[ValidationResult[_]]] = fieldValidator(input._1)
    Future.sequence(validations) map { results =>
      results.flatMap {
        case Left(value) => value
        case Right(_)    => Seq.empty
      } match {
        case errors if errors.isEmpty => Right(input._1)
        case errors                   => Left(errors)
      }
    }
  }
}

/**
 * Factory for Creating Anonymous Asynchronous Object Validators.
 *
 * Use this in place of ObjectValidator where any of the field validations are asynchronous
 *
 */
object AsyncObjectValidator {
  def apply[A](validators: AsyncFieldValidator[A]): AsyncObjectValidator[A] = {
    new Object() with AsyncObjectValidator[A] {
      def fieldValidator: AsyncFieldValidator[A] = validators
    }
  }
}
