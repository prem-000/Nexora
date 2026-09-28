package com.fury.peerconnect.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * High-performance SQLite cache for:
 * 1. OpenFreeMap vector tiles (.pbf) — table: tiles
 *    Schema: z INTEGER, x INTEGER, y INTEGER, data BLOB, fetched_at INTEGER, PRIMARY KEY(z, x, y)
 * 2. Overpass POI results — table: poi_cache
 *    Schema: cache_key TEXT PRIMARY KEY, json TEXT, fetched_at INTEGER
 *
 * Starts empty on first install — no bundled data.
 */
class TileCacheDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creating local vector tile cache table: $TABLE_TILES")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TILES (
                z INTEGER NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                data BLOB NOT NULL,
                fetched_at INTEGER NOT NULL,
                PRIMARY KEY (z, x, y)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tiles_lookup ON $TABLE_TILES(z, x, y)")

        Log.i(TAG, "Creating POI cache table: $TABLE_POI")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_POI (
                cache_key TEXT NOT NULL,
                json TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                PRIMARY KEY (cache_key)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Add poi_cache table for users upgrading from version 1
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_POI (
                    cache_key TEXT NOT NULL,
                    json TEXT NOT NULL,
                    fetched_at INTEGER NOT NULL,
                    PRIMARY KEY (cache_key)
                )
                """.trimIndent()
            )
        }
    }

    // ─── Tile cache methods ─────────────────────────────────────────────────

    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        val db = readableDatabase
        return try {
            db.rawQuery(
                "SELECT data FROM $TABLE_TILES WHERE z = ? AND x = ? AND y = ? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getBlob(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching tile ($z, $x, $y)", e)
            null
        }
    }

    fun putTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply {
                put("z", z)
                put("x", x)
                put("y", y)
                put("data", data)
                put("fetched_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_TILES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting tile ($z, $x, $y)", e)
        }
    }

    fun hasTile(z: Int, x: Int, y: Int): Boolean {
        val db = readableDatabase
        return try {
            db.rawQuery(
                "SELECT 1 FROM $TABLE_TILES WHERE z = ? AND x = ? AND y = ? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { cursor ->
                cursor.moveToFirst()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getTileCount(): Long {
        val db = readableDatabase
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_TILES", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun getTotalSizeBytes(): Long {
        val db = readableDatabase
        return try {
            db.rawQuery("SELECT SUM(LENGTH(data)) FROM $TABLE_TILES", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun getCachedTilesInBounds(
        minZ: Int,
        maxZ: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int
    ): List<Triple<Int, Int, Int>> {
        val result = mutableListOf<Triple<Int, Int, Int>>()
        val db = readableDatabase
        try {
            db.rawQuery(
                """
                SELECT z, x, y FROM $TABLE_TILES 
                WHERE z >= ? AND z <= ? AND x >= ? AND x <= ? AND y >= ? AND y <= ?
                """.trimIndent(),
                arrayOf(
                    minZ.toString(), maxZ.toString(),
                    minX.toString(), maxX.toString(),
                    minY.toString(), maxY.toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying cached tiles in bounds", e)
        }
        return result
    }

    // ─── POI cache methods ──────────────────────────────────────────────────

    /**
     * Returns the cached Overpass JSON string for the given area key, or null if not cached.
     */
    fun getPoisForArea(cacheKey: String): String? {
        val db = readableDatabase
        return try {
            db.rawQuery(
                "SELECT json FROM $TABLE_POI WHERE cache_key = ? LIMIT 1",
                arrayOf(cacheKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading POI cache for key=$cacheKey", e)
            null
        }
    }

    /**
     * Stores (or replaces) the Overpass JSON for the given area key.
     */
    fun cachePois(cacheKey: String, json: String) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply {
                put("cache_key", cacheKey)
                put("json", json)
                put("fetched_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_POI, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "Error caching POIs for key=$cacheKey", e)
        }
    }

    fun getPoiCacheCount(): Long {
        val db = readableDatabase
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_POI", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val TAG = "TileCacheDbHelper"
        private const val DATABASE_NAME = "tile_cache.db"
        private const val DATABASE_VERSION = 2  // Bumped for poi_cache table
        private const val TABLE_TILES = "tiles"
        private const val TABLE_POI = "poi_cache"

        @Volatile
        private var instance: TileCacheDbHelper? = null

        fun getInstance(context: Context): TileCacheDbHelper {
            return instance ?: synchronized(this) {
                instance ?: TileCacheDbHelper(context).also { instance = it }
            }
        }
    }
}
