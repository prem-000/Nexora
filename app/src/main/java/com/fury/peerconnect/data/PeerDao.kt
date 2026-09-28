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

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeerByDeviceId(deviceId: String): PeerEntity?

    @Query("DELETE FROM peers WHERE name = :peerName")
    suspend fun deletePeerByName(peerName: String)

    @Query("UPDATE peers SET isOnline = :isOnline, isReachable = :isReachable WHERE name = :peerName")
    suspend fun updatePeerOnlineStatus(peerName: String, isOnline: Boolean, isReachable: Boolean)

    @Query("UPDATE peers SET isReachable = :isReachable, isOnline = CASE WHEN :isReachable = 0 THEN 0 ELSE isOnline END, nextHop = :nextHop, hopDistance = :hopDistance, lastSeenTimestamp = :lastSeen WHERE name = :peerName")
    suspend fun updateReachability(
        peerName: String,
        isReachable: Boolean,
        nextHop: String?,
        hopDistance: Int,
        lastSeen: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM peers WHERE isConnected = 1 ORDER BY isOnline DESC, isReachable DESC, lastSeenTimestamp DESC, name ASC")
    suspend fun getConnectedPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE connectionStatus = 'REQUEST_RECEIVED' ORDER BY lastSeenTimestamp DESC")
    suspend fun getConnectionRequests(): List<PeerEntity>

    @Query("UPDATE peers SET connectionStatus = :status, isConnected = :isConnected WHERE name = :peerName")
    suspend fun updateConnectionStatus(peerName: String, status: String, isConnected: Boolean)

    @Query("UPDATE peers SET avatarId = :avatarId, profileVersion = :version WHERE name = :peerName")
    suspend fun updatePeerProfile(peerName: String, avatarId: String, version: Long)

    @Query("UPDATE peers SET avatarId = :avatarId WHERE name = :peerName")
    suspend fun updatePeerAvatar(peerName: String, avatarId: String)

    @Query("UPDATE peers SET latitude = :lat, longitude = :lon, lastDistanceMeters = :distance WHERE name = :peerName")
    suspend fun updatePeerLocation(peerName: String, lat: Double?, lon: Double?, distance: Double?)
}
