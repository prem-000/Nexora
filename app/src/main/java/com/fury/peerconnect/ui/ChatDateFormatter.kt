package com.fury.peerconnect.ui

import com.fury.peerconnect.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ChatDateFormatter {

    fun formatDateSeparator(
        timestamp: Long,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val validTimestamp = if (timestamp > 0L) timestamp else nowMillis
        val calMsg = Calendar.getInstance(timeZone, locale).apply { timeInMillis = validTimestamp }
        val calNow = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val calYesterday = Calendar.getInstance(timeZone, locale).apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }

        val isToday = calMsg.get(Calendar.ERA) == calNow.get(Calendar.ERA) &&
                calMsg.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                calMsg.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        if (isToday) return "Today"

        val isYesterday = calMsg.get(Calendar.ERA) == calYesterday.get(Calendar.ERA) &&
                calMsg.get(Calendar.YEAR) == calYesterday.get(Calendar.YEAR) &&
                calMsg.get(Calendar.DAY_OF_YEAR) == calYesterday.get(Calendar.DAY_OF_YEAR)

        if (isYesterday) return "Yesterday"

        val isCurrentYear = calMsg.get(Calendar.ERA) == calNow.get(Calendar.ERA) &&
                calMsg.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)

        return if (isCurrentYear) {
            val sdf = SimpleDateFormat("MMMM d", locale).apply { this.timeZone = timeZone }
            sdf.format(Date(validTimestamp))
        } else {
            val sdf = SimpleDateFormat("MMMM d, yyyy", locale).apply { this.timeZone = timeZone }
            sdf.format(Date(validTimestamp))
        }
    }

    fun isSameCalendarDay(
        timeA: Long,
        timeB: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): Boolean {
        val calA = Calendar.getInstance(timeZone, locale).apply { timeInMillis = timeA }
        val calB = Calendar.getInstance(timeZone, locale).apply { timeInMillis = timeB }
        return calA.get(Calendar.ERA) == calB.get(Calendar.ERA) &&
                calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
                calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    fun groupMessagesWithDateSeparators(
        history: List<MessageEntity>,
        myNickName: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): List<ChatItem> {
        val sorted = history.sortedBy { it.timestamp }
        val result = ArrayList<ChatItem>(sorted.size + 10)
        var lastDayCal: Calendar? = null

        for (entity in sorted) {
            val validTimestamp = if (entity.timestamp > 0L) entity.timestamp else nowMillis
            val cal = Calendar.getInstance(timeZone, locale).apply { timeInMillis = validTimestamp }

            val isNewDay = lastDayCal == null ||
                    lastDayCal.get(Calendar.ERA) != cal.get(Calendar.ERA) ||
                    lastDayCal.get(Calendar.YEAR) != cal.get(Calendar.YEAR) ||
                    lastDayCal.get(Calendar.DAY_OF_YEAR) != cal.get(Calendar.DAY_OF_YEAR)

            if (isNewDay) {
                val dateText = formatDateSeparator(validTimestamp, nowMillis, timeZone, locale)
                result.add(ChatItem.DateSeparator(dateText = dateText, timestamp = validTimestamp))
                lastDayCal = cal
            }

            result.add(
                ChatItem.Message(
                    id = entity.id,
                    senderName = entity.senderId,
                    messageBody = entity.text,
                    time = entity.timestamp,
                    isMe = entity.senderId == myNickName,
                    deliveryStatus = entity.deliveryStatus,
                    messageType = entity.messageType,
                    fileName = entity.fileName,
                    localPath = entity.localPath,
                    fileSize = entity.fileSize,
                    transferStatus = entity.transferStatus,
                    alertId = entity.alertId
                )
            )
        }
        return result
    }
}
