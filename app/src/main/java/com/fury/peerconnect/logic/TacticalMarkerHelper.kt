package com.fury.peerconnect.logic

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Generates custom circular avatar markers and tactical icons for MapLibre MapView.
 * Provides live/stale status rings, avatar fallback initials, and distinct POI/Alert badges.
 */
object TacticalMarkerHelper {

    private val iconCache = ConcurrentHashMap<String, Icon>()

    fun getSelfMarkerIcon(context: Context): Icon {
        val cacheKey = "self_marker"
        return iconCache.getOrPut(cacheKey) {
            val bmp = createSelfMarkerBitmap(context)
            IconFactory.getInstance(context).fromBitmap(bmp)
        }
    }

    fun getPeerMarkerIcon(
        context: Context,
        peerName: String,
        avatarId: String?,
        isLive: Boolean
    ): Icon {
        val safeAvatar = avatarId?.trim() ?: "avatar_01"
        val cacheKey = "peer_${peerName}_${safeAvatar}_${isLive}"
        return iconCache.getOrPut(cacheKey) {
            val bmp = createPeerMarkerBitmap(context, peerName, safeAvatar, isLive)
            IconFactory.getInstance(context).fromBitmap(bmp)
        }
    }

    fun getAlertMarkerIcon(context: Context): Icon {
        val cacheKey = "alert_marker"
        return iconCache.getOrPut(cacheKey) {
            val bmp = createAlertMarkerBitmap(context)
            IconFactory.getInstance(context).fromBitmap(bmp)
        }
    }

    fun getPoiMarkerIcon(context: Context, type: OverpassPoiEngine.PoiType): Icon {
        val cacheKey = "poi_${type.name}"
        return iconCache.getOrPut(cacheKey) {
            val bmp = createPoiMarkerBitmap(context, type)
            IconFactory.getInstance(context).fromBitmap(bmp)
        }
    }

    fun getEmergencyPoiMarkerIcon(context: Context, category: com.fury.peerconnect.data.EmergencyCategory): Icon {
        val cacheKey = "emergency_poi_${category.name}"
        return iconCache.getOrPut(cacheKey) {
            val bmp = createEmergencyPoiMarkerBitmap(context, category)
            IconFactory.getInstance(context).fromBitmap(bmp)
        }
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    private fun createSelfMarkerBitmap(context: Context): Bitmap {
        val size = dpToPx(context, 38f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f

        // Outer soft pulse aura
        val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            alpha = 70
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 2f, auraPaint)

        // White border ring
        val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - dpToPx(context, 4f), whiteBorderPaint)

        // Core dot
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0284C7")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - dpToPx(context, 7f), corePaint)

        return bmp
    }

    private fun createPeerMarkerBitmap(
        context: Context,
        peerName: String,
        avatarId: String,
        isLive: Boolean
    ): Bitmap {
        val size = dpToPx(context, 46f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = (size / 2f) - dpToPx(context, 4f)

        // 1. Draw outer status ring
        val ringColor = if (isLive) Color.parseColor("#10B981") else Color.parseColor("#64748B")
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, if (isLive) 3.5f else 2.5f)
        }

        // Drop shadow / dark contrast backing
        val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, backingPaint)

        // 2. Draw avatar or initials inside circular clip
        val innerRadius = radius - dpToPx(context, 2f)
        val avatarResId = AvatarManager.getDrawableId(avatarId)
        val avatarBmp = try {
            val drawable = ContextCompat.getDrawable(context, avatarResId)
            if (drawable != null) {
                val dSize = (innerRadius * 2).toInt()
                val b = Bitmap.createBitmap(dSize, dSize, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                drawable.setBounds(0, 0, dSize, dSize)
                drawable.draw(c)
                b
            } else null
        } catch (_: Exception) {
            null
        }

        if (avatarBmp != null) {
            val shader = BitmapShader(avatarBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            val scale = (innerRadius * 2f) / min(avatarBmp.width, avatarBmp.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(center - innerRadius, center - innerRadius)
            shader.setLocalMatrix(matrix)

            val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = shader
            }
            canvas.drawCircle(center, center, innerRadius, avatarPaint)
        } else {
            // Initials fallback
            val initials = getInitials(peerName)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dpToPx(context, 14f)
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val textBounds = Rect()
            textPaint.getTextBounds(initials, 0, initials.length, textBounds)
            val yOffset = center - textBounds.exactCenterY()
            canvas.drawText(initials, center, yOffset, textPaint)
        }

        // Draw the ring border on top
        canvas.drawCircle(center, center, radius, ringPaint)

        // 3. If Live, draw an active green indicator badge on the top-right
        if (isLive) {
            val dotCenterOffset = radius * 0.7f
            val dotX = center + dotCenterOffset
            val dotY = center - dotCenterOffset
            val dotRadius = dpToPx(context, 5.5f)

            val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(dotX, dotY, dotRadius + dpToPx(context, 1.2f), dotBorderPaint)

            val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22C55E")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(dotX, dotY, dotRadius, liveDotPaint)
        }

        return bmp
    }

    private fun createAlertMarkerBitmap(context: Context): Bitmap {
        val size = dpToPx(context, 42f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = center - dpToPx(context, 3f)

        // Red background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, bgPaint)

        // White border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 2.5f)
        }
        canvas.drawCircle(center, center, radius, borderPaint)

        // Exclamation mark / warning icon
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(context, 20f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds("!", 0, 1, textBounds)
        val yOffset = center - textBounds.exactCenterY()
        canvas.drawText("!", center, yOffset, textPaint)

        return bmp
    }

    private fun createPoiMarkerBitmap(context: Context, type: OverpassPoiEngine.PoiType): Bitmap {
        val size = dpToPx(context, 36f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = center - dpToPx(context, 2f)

        val (bgColor, symbol) = when (type) {
            OverpassPoiEngine.PoiType.HOSPITAL -> Pair("#DC2626", "+")
            OverpassPoiEngine.PoiType.PHARMACY -> Pair("#16A34A", "Rx")
            OverpassPoiEngine.PoiType.POLICE -> Pair("#2563EB", "★")
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(bgColor)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 2f)
        }
        canvas.drawCircle(center, center, radius, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(context, if (symbol.length > 1) 12f else 17f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(symbol, 0, symbol.length, textBounds)
        val yOffset = center - textBounds.exactCenterY()
        canvas.drawText(symbol, center, yOffset, textPaint)

        return bmp
    }

    private fun createEmergencyPoiMarkerBitmap(
        context: Context,
        category: com.fury.peerconnect.data.EmergencyCategory
    ): Bitmap {
        val size = dpToPx(context, 38f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = center - dpToPx(context, 2f)

        val (bgColor, symbol) = when (category) {
            com.fury.peerconnect.data.EmergencyCategory.HOSPITAL -> Pair(category.badgeColorHex, "+")
            com.fury.peerconnect.data.EmergencyCategory.POLICE -> Pair(category.badgeColorHex, "★")
            com.fury.peerconnect.data.EmergencyCategory.FIRE_STATION -> Pair(category.badgeColorHex, "F")
            com.fury.peerconnect.data.EmergencyCategory.AMBULANCE -> Pair(category.badgeColorHex, "A")
            com.fury.peerconnect.data.EmergencyCategory.PHARMACY -> Pair(category.badgeColorHex, "Rx")
            com.fury.peerconnect.data.EmergencyCategory.RAILWAY_POLICE -> Pair(category.badgeColorHex, "RPF")
            com.fury.peerconnect.data.EmergencyCategory.RAILWAY_HELP -> Pair(category.badgeColorHex, "RLY")
            com.fury.peerconnect.data.EmergencyCategory.SHELTER -> Pair(category.badgeColorHex, "S")
        }

        // Contrast dark backing
        val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, backingPaint)

        // Category core background fill
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(bgColor)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius - dpToPx(context, 2f), bgPaint)

        // Outer white border ring
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 2f)
        }
        canvas.drawCircle(center, center, radius, borderPaint)

        // Text emblem
        val textSizeDp = when {
            symbol.length > 2 -> 9f
            symbol.length == 2 -> 11f
            else -> 17f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(context, textSizeDp)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(symbol, 0, symbol.length, textBounds)
        val yOffset = center - textBounds.exactCenterY()
        canvas.drawText(symbol, center, yOffset, textPaint)

        return bmp
    }

    private fun getInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "P"
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.US)
            else -> "${parts[0].take(1)}${parts[1].take(1)}".uppercase(Locale.US)
        }
    }

    fun clearCache() {
        iconCache.clear()
    }
}
