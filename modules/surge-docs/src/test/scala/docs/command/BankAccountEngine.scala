// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package docs.command

import surge.internal.utils.DiagnosticContextFuturePropagation

import java.util.UUID
import surge.scaladsl.command.SurgeCommand

// #bank_account_engine_class
object BankAccountEngine {
  implicit val ec = DiagnosticContextFuturePropagation.global
  lazy val surgeEngine: SurgeCommand[UUID, BankAccount, BankAccountCommand, BankAccountEvent] = {
    val engine = SurgeCommand(BankAccountSurgeModel)
    engine.start()
    engine
  }
}
// #bank_account_engine_class
