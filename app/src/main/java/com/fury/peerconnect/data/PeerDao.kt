package com.fury.peerconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY isOnline DESC, isReachable DESC, lastSeenTimestamp DESC")
    suspend fun getAllPeers(): List<PeerEntity>

    @Query("UPDATE peers SET isOnline = 0, isReachable = 0, nextHop = NULL, hopDistance = 0")
    suspend fun setAllOffline()

    @Query("UPDATE peers SET isOnline = 0, isReachable = 0, nextHop = NULL, hopDistance = 0, lastSeenTimestamp = :lastSeen WHERE name = :peerName")
    suspend fun markPeerOffline(peerName: String, lastSeen: Long = System.currentTimeMillis())

    @Query("SELECT EXISTS(SELECT 1 FROM peers WHERE name = :peerName)")
    suspend fun isKnownPeer(peerName: String): Boolean

    @Query("SELECT * FROM peers WHERE name = :peerName LIMIT 1")
    suspend fun getPeerByName(peerName: String): PeerEntity?

    @Query("UPDATE peers SET isReachable = :isReachable, isOnline = CASE WHEN :isReachable = 0 THEN 0 ELSE isOnline END, nextHop = :nextHop, hopDistance = :hopDistance, lastSeenTimestamp = :lastSeen WHERE name = :peerName")
    suspend fun updateReachability(
        peerName: String,
        isReachable: Boolean,
        nextHop: String?,
        hopDistance: Int,
        lastSeen: Long = System.currentTimeMillis()
    )
}
