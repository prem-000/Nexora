package com.fury.peerconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface MessageDao {
    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :msgId LIMIT 1")
    suspend fun getMessageById(msgId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE (senderId = :myId AND receiverId = :friendId) OR (senderId = :friendId AND receiverId = :myId) ORDER BY timestamp ASC")
    suspend fun getChatHistory(myId: String, friendId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE receiverId = :friendId AND isSent = 0")
    suspend fun getUnsentMessages(friendId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE alertId = :alertId ORDER BY timestamp ASC")
    suspend fun getAlertThreadMessages(alertId: String): List<MessageEntity>

    @Query("UPDATE messages SET isSent = 1, deliveryStatus = 'FORWARDED' WHERE id = :msgId")
    suspend fun markAsSent(msgId: Int)

    @Query("UPDATE messages SET deliveryStatus = :status, isSent = :isSent WHERE id = :msgId")
    suspend fun updateDeliveryStatus(msgId: Int, status: String, isSent: Boolean)

    @Query("""
        UPDATE messages 
        SET deliveryStatus = :status, 
            isSent = CASE WHEN :status IN ('SENT', 'FORWARDED', 'DELIVERED', 'READ') THEN 1 ELSE isSent END 
        WHERE id = :msgId 
          AND (
              (:status = 'READ' AND deliveryStatus IN ('PENDING', 'SENDING', 'FORWARDED', 'SENT', 'DELIVERED', 'READ')) OR
              (:status = 'DELIVERED' AND deliveryStatus IN ('PENDING', 'SENDING', 'FORWARDED', 'SENT', 'DELIVERED')) OR
              (:status IN ('SENT', 'FORWARDED') AND deliveryStatus IN ('PENDING', 'SENDING', 'FORWARDED', 'SENT'))
          )
    """)
    suspend fun updateDeliveryStatusMonotonic(msgId: Int, status: String): Int

    @Query("SELECT * FROM messages WHERE senderId = :senderId AND receiverId = :receiverId AND senderMessageId IS NOT NULL AND deliveryStatus != 'READ'")
    suspend fun getUnreadIncomingMessages(senderId: String, receiverId: String): List<MessageEntity>

    @Query("UPDATE messages SET deliveryStatus = 'READ' WHERE senderId = :senderId AND receiverId = :receiverId AND deliveryStatus != 'READ'")
    suspend fun markIncomingMessagesAsRead(senderId: String, receiverId: String): Int

    @Query("SELECT * FROM messages WHERE text LIKE '[FILE]:%' OR text LIKE '📄 Shared a file:%' ORDER BY timestamp DESC")
    suspend fun getAllFileMessages(): List<MessageEntity>

    @Query("UPDATE messages SET text = :text, localPath = :localPath, transferStatus = :status, fileSize = :size WHERE id = :msgId")
    suspend fun updateFileDetails(msgId: Long, text: String, localPath: String, status: String, size: Long)

    @Query("UPDATE messages SET transferStatus = :status WHERE id = :msgId")
    suspend fun updateTransferStatus(msgId: Long, status: String)
}
