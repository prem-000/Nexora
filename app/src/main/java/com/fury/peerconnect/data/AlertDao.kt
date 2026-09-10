package com.fury.peerconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlert(alert: AlertEntity): Long

    @Update
    suspend fun updateAlert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT 100")
    suspend fun getAllAlerts(): List<AlertEntity>

    @Query("SELECT * FROM alerts WHERE alertId = :alertId LIMIT 1")
    suspend fun getAlertByAlertId(alertId: String): AlertEntity?

    @Query("SELECT * FROM alerts WHERE id = :id LIMIT 1")
    suspend fun getAlertById(id: Int): AlertEntity?

    @Query("SELECT * FROM alerts WHERE isCustodyActive = 1 AND (expiresAt = 0 OR expiresAt > :now) ORDER BY timestamp DESC")
    suspend fun getActiveCustodyAlerts(now: Long): List<AlertEntity>

    @Query("UPDATE alerts SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    @Query("UPDATE alerts SET isCustodyActive = :isActive WHERE alertId = :alertId")
    suspend fun setCustodyActive(alertId: String, isActive: Boolean)

    @Query("DELETE FROM alerts")
    suspend fun clearAlerts()
}
