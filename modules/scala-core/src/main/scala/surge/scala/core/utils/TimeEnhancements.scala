// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.scala.core.utils

import java.time.temporal.ChronoUnit
import java.time.{ Instant, LocalDate, LocalDateTime, ZoneOffset }

object TimeEnhancements {
  implicit class JavaTimeInstantExtensions(instant: Instant) {
    def toLocalDate: LocalDate = LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toLocalDate

    def plusMinutes(minutes: Int): Instant = {
      instant.plus(minutes, ChronoUnit.MINUTES)
    }

    def plusHours(hours: Int): Instant = {
      instant.plus(hours, ChronoUnit.HOURS)
    }

    def plusDays(days: Int): Instant = {
      instant.plus(days, ChronoUnit.DAYS)
    }

    def truncatedToSeconds: Instant = instant.truncatedTo(ChronoUnit.SECONDS)
  }
}