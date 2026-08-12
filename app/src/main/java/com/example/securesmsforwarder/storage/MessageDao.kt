package com.example.securesmsforwarder.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isRead = 0 ORDER BY timestamp DESC")
    suspend fun getUnreadMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE state IN ('QUEUED', 'ENCRYPTED', 'TRANSMITTING', 'PERSISTED_BY_B') ORDER BY sequenceNumber ASC")
    fun getPendingMessages(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET state = :newState WHERE messageId = :id")
    suspend fun updateMessageState(id: String, newState: MessageState)

    @Query("UPDATE messages SET isRead = 1 WHERE messageId = :id")
    suspend fun markAsRead(id: String)

    @Query("DELETE FROM messages WHERE messageId = :id")
    suspend fun deleteMessage(id: String)
    
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT * FROM messages WHERE senderDeviceId = :sender AND timestamp >= :minTime AND timestamp <= :maxTime")
    suspend fun getRecentMessagesFromSender(sender: String, minTime: Long, maxTime: Long): List<MessageEntity>
}
