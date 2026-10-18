package com.fury.peerconnect.data

import android.content.Context
import android.util.Log
import com.fury.peerconnect.logic.DistanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.cos

/**
 * Repository for managing local emergency POIs.
 * Prepopulates authentic OpenStreetMap regional extract records into Room database
 * on first startup, ensuring 100% offline access with zero network dependencies.
 */
class EmergencyPoiRepository private constructor(
    private val context: Context,
    private val database: AppDatabase
) {
    private val poiDao = database.emergencyPoiDao()

    suspend fun ensureInitialized(): Int = withContext(Dispatchers.IO) {
        val existingCount = poiDao.getPoiCount()
        if (existingCount > 0) {
            Log.d(TAG, "Emergency POI database already initialized with $existingCount records")
            return@withContext existingCount
        }

        try {
            val assetJson = readAssetFile(context, ASSET_FILE_NAME)
            if (assetJson.isNullOrBlank()) {
                Log.w(TAG, "Emergency POI asset file '$ASSET_FILE_NAME' is empty or missing")
                return@withContext 0
            }

            val pois = parsePoisFromJson(assetJson)
            if (pois.isNotEmpty()) {
                poiDao.insertPois(pois)
                Log.i(TAG, "Provisioned ${pois.size} emergency POIs into local Room database")
            }
            poiDao.getPoiCount()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize emergency POI database from assets", e)
            0
        }
    }

    suspend fun getAllPois(categoryFilter: EmergencyCategory? = null): List<EmergencyPoiEntity> = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (categoryFilter != null) {
            poiDao.getPoisByCategory(categoryFilter.id)
        } else {
            poiDao.getAllPois()
        }
    }

    suspend fun getPoisNear(
        lat: Double,
        lon: Double,
        radiusMeters: Double = 25000.0,
        categoryFilter: EmergencyCategory? = null
    ): List<EmergencyPoiEntity> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val latDegDelta = radiusMeters / 111320.0
        val lonDegDelta = radiusMeters / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))

        val minLat = lat - latDegDelta
        val maxLat = lat + latDegDelta
        val minLon = lon - lonDegDelta
        val maxLon = lon + lonDegDelta

        val candidates = if (categoryFilter != null) {
            poiDao.getPoisInBoundingBoxWithCategory(minLat, maxLat, minLon, maxLon, categoryFilter.id)
        } else {
            poiDao.getPoisInBoundingBox(minLat, maxLat, minLon, maxLon)
        }

        // Exact haversine filter & sort by proximity
        candidates.map { poi ->
            val dist = DistanceEngine.calculateHaversineDistance(lat, lon, poi.latitude, poi.longitude)
            Pair(poi, dist)
        }.filter { it.second <= radiusMeters }
            .sortedBy { it.second }
            .map { it.first }
    }

    suspend fun searchPois(
        query: String,
        categoryFilter: EmergencyCategory? = null
    ): List<EmergencyPoiEntity> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext getAllPois(categoryFilter)
        }

        if (categoryFilter != null) {
            poiDao.searchPoisWithCategory(cleanQuery, categoryFilter.id)
        } else {
            poiDao.searchPois(cleanQuery)
        }
    }

    suspend fun getPoiById(id: String): EmergencyPoiEntity? = withContext(Dispatchers.IO) {
        ensureInitialized()
        poiDao.getPoiById(id)
    }

    companion object {
        private const val TAG = "EmergencyPoiRepository"
        const val ASSET_FILE_NAME = "emergency_pois.json"

        @Volatile
        private var instance: EmergencyPoiRepository? = null

        fun getInstance(context: Context): EmergencyPoiRepository {
            return instance ?: synchronized(this) {
                instance ?: EmergencyPoiRepository(
                    context.applicationContext,
                    AppDatabase.getDatabase(context.applicationContext)
                ).also { instance = it }
            }
        }

        fun parsePoisFromJson(jsonString: String): List<EmergencyPoiEntity> {
            val list = mutableListOf<EmergencyPoiEntity>()
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val name = obj.optString("name", "")
                    val category = obj.optString("category", "")
                    val latitude = obj.optDouble("latitude", Double.NaN)
                    val longitude = obj.optDouble("longitude", Double.NaN)

                    if (id.isNotBlank() && name.isNotBlank() && category.isNotBlank() &&
                        !latitude.isNaN() && !longitude.isNaN()) {
                        list.add(
                            EmergencyPoiEntity(
                                id = id,
                                name = name,
                                category = category,
                                subCategory = obj.optString("subCategory").takeIf { !it.isNullOrBlank() },
                                latitude = latitude,
                                longitude = longitude,
                                phone = obj.optString("phone").takeIf { !it.isNullOrBlank() },
                                emergencyPhone = obj.optString("emergencyPhone").takeIf { !it.isNullOrBlank() },
                                address = obj.optString("address").takeIf { !it.isNullOrBlank() },
                                operator = obj.optString("operator").takeIf { !it.isNullOrBlank() },
                                capabilities = obj.optString("capabilities").takeIf { !it.isNullOrBlank() },
                                source = obj.optString("source", "OpenStreetMap Regional Extract • ODbL"),
                                isVerified = obj.optBoolean("isVerified", true)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing POI JSON array", e)
            }
            return list
        }

        fun readAssetFile(context: Context, filename: String): String? {
            return try {
                context.assets.open(filename).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open asset file: $filename", e)
                null
            }
        }
    }
}
