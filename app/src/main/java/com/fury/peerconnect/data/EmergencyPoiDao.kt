package com.fury.peerconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room Data Access Object for locally stored emergency facilities.
 * Supports offline search, category filtering, and geographic bounding box retrieval.
 */
@Dao
interface EmergencyPoiDao {

    @Query("SELECT * FROM emergency_pois ORDER BY name ASC")
    suspend fun getAllPois(): List<EmergencyPoiEntity>

    @Query("SELECT * FROM emergency_pois WHERE category = :category ORDER BY name ASC")
    suspend fun getPoisByCategory(category: String): List<EmergencyPoiEntity>

    @Query("SELECT * FROM emergency_pois WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon ORDER BY name ASC")
    suspend fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<EmergencyPoiEntity>

    @Query("SELECT * FROM emergency_pois WHERE category = :category AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon ORDER BY name ASC")
    suspend fun getPoisInBoundingBoxWithCategory(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, category: String): List<EmergencyPoiEntity>

    @Query("SELECT * FROM emergency_pois WHERE name LIKE '%' || :query || '%' OR operator LIKE '%' || :query || '%' OR address LIKE '%' || :query || '%' OR subCategory LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchPois(query: String): List<EmergencyPoiEntity>

    @Query("SELECT * FROM emergency_pois WHERE category = :category AND (name LIKE '%' || :query || '%' OR operator LIKE '%' || :query || '%' OR address LIKE '%' || :query || '%' OR subCategory LIKE '%' || :query || '%') ORDER BY name ASC")
    suspend fun searchPoisWithCategory(query: String, category: String): List<EmergencyPoiEntity>

    @Query("SELECT * FROM emergency_pois WHERE id = :id LIMIT 1")
    suspend fun getPoiById(id: String): EmergencyPoiEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPois(pois: List<EmergencyPoiEntity>)

    @Query("SELECT COUNT(*) FROM emergency_pois")
    suspend fun getPoiCount(): Int

    @Query("DELETE FROM emergency_pois")
    suspend fun clearAll(): Int
}
