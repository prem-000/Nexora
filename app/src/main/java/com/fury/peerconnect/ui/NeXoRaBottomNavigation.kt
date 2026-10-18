package com.fury.peerconnect.ui

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fury.peerconnect.R
import kotlin.math.roundToInt

/**
 * The five destinations supported by NeXoRa, preserved in strict sequence:
 * 1. Home
 * 2. Chats
 * 3. Map
 * 4. Alerts
 * 5. Settings
 */
enum class NeXoRaNavDestination(
    @IdRes val id: Int,
    val title: String,
    @DrawableRes val iconRes: Int
) {
    HOME(R.id.nav_home, "Home", R.drawable.ic_nav_home),
    CHATS(R.id.nav_messages, "Chats", R.drawable.ic_nav_messages),
    MAP(R.id.nav_map, "Map", R.drawable.ic_nav_map),
    ALERTS(R.id.nav_alerts, "Alerts", R.drawable.ic_nav_alerts),
    SETTINGS(R.id.nav_settings, "Settings", R.drawable.ic_nav_settings);

    companion object {
        fun fromId(id: Int): NeXoRaNavDestination =
            entries.firstOrNull { it.id == id } ?: HOME
    }
}

/**
 * Animated Icon-Only NeXoRa Bottom Navigation Bar.
 *
 * Requirements fulfilled:
 * 1. Icon-only navigation: all text labels (Home, Chats, Map, Alerts, Settings) are removed from display.
 * 2. All 5 navigation destinations and their click behaviors are preserved.
 * 3. Exact project colors are reused:
 *    - Light background: Color(0xFFFFFFFF), Dark: Color(0xFF1E1E1E)
 *    - Subtle top divider: Color(0xFFE5E7EB) (nexora_border), Dark: Color(0xFF2C2C2E)
 *    - Active indicator: Color(0xFFDCFCE7) (nexora_green_soft), Dark: Color(0xFF144D29)
 *    - Selected icon: Color(0xFF16A34A) (nexora_green)
 *    - Unselected icon: Color(0xFF6B7280) (nexora_text_secondary), Dark: Color(0xFF9CA3AF)
 *    - Badge container: Color(0xFFE53935) (nexora_danger), Content: Color.White
 * 4. Subtle top divider preserved at the top of the bar.
 * 5. Green selected-item indicator pill and icon color styling preserved.
 * 6. Smooth selection animations:
 *    - Active indicator: smoothly slides and dynamically expands/settles to the newly selected destination.
 *    - Selected icon: subtle scale-up (1.15f) with a smooth spring transition.
 *    - Unselected icons: smoothly return to normal size (1.0f) and inactive color.
 * 7. Native Jetpack Compose animation APIs: animateFloatAsState, animateDpAsState, animateColorAsState.
 * 8. Lightweight and responsive: offset calculation executes during layout/placement phase.
 * 9. Standard 80dp height, window insets, pill dimensions (64dp x 36dp), full touch targets (48dp+), and TalkBack descriptions.
 * 10. Fully compatible with both Light and Dark themes.
 */
@Composable
fun NeXoRaBottomNavigationBar(
    selectedDestination: NeXoRaNavDestination,
    onDestinationSelected: (NeXoRaNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    chatsUnreadCount: Int = 0,
    alertsUnreadCount: Int = 0,
    isDark: Boolean = false
) {
    // Exact colors defined in the application - consistently light/white theme
    val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB)
    val softGreenIndicator = if (isDark) Color(0xFF144D29) else Color(0xFFDCFCE7)
    val nexoraGreen = Color(0xFF16A34A)
    val unselectedIconColor = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
    val badgeContainerColor = Color(0xFFE53935)
    val badgeContentColor = Color.White

    val colorScheme = androidx.compose.material3.lightColorScheme(
        surface = surfaceColor,
        background = surfaceColor,
        primary = nexoraGreen,
        secondaryContainer = softGreenIndicator
    )

    androidx.compose.material3.MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = surfaceColor,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Subtle top divider line separating bottom nav from content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(dividerColor)
                )

                // Content container respecting system navigation bar insets
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(80.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val destinations = NeXoRaNavDestination.entries
                    val itemCount = destinations.size
                    val selectedIndex = destinations.indexOf(selectedDestination).coerceAtLeast(0)
                    val density = LocalDensity.current

                    // Animate indicator position smoothly between destinations using spring physics
                    val animatedIndex by animateFloatAsState(
                        targetValue = selectedIndex.toFloat(),
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = 380f
                        ),
                        label = "indicatorIndex"
                    )

                    // Dynamic expansion bonus while sliding to give fluid, responsive momentum
                    val isMoving = kotlin.math.abs(animatedIndex - selectedIndex) > 0.05f
                    val targetPillWidth = if (isMoving) 72.dp else 64.dp
                    val animatedPillWidth by animateDpAsState(
                        targetValue = targetPillWidth,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "indicatorWidth"
                    )

                    val pillHeight = 36.dp

                    // Active indicator pill smoothly sliding and expanding across destinations
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                val totalWidthPx = if (constraints.maxWidth in 1..99999) {
                                    constraints.maxWidth.toFloat()
                                } else {
                                    with(density) { 360.dp.toPx() }
                                }
                                val itemWidthPx = totalWidthPx / itemCount
                                val currentWidthPx = with(density) { animatedPillWidth.toPx() }
                                val centerX = (animatedIndex + 0.5f) * itemWidthPx
                                val left = centerX - (currentWidthPx / 2f)
                                IntOffset(left.roundToInt(), 0)
                            }
                            .size(width = animatedPillWidth, height = pillHeight)
                            .background(
                                color = softGreenIndicator,
                                shape = RoundedCornerShape(percent = 50)
                            )
                    )

                // 5 Navigation items row with icon-only presentation
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    destinations.forEach { destination ->
                        val isSelected = destination == selectedDestination

                        // Smooth color transition
                        val animatedIconColor by animateColorAsState(
                            targetValue = if (isSelected) nexoraGreen else unselectedIconColor,
                            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                            label = "iconColor_${destination.name}"
                        )

                        // Subtle scale-up when selected, smooth return to 1.0f when unselected
                        val animatedScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "scale_${destination.name}"
                        )

                        val unreadCount = when (destination) {
                            NeXoRaNavDestination.CHATS -> chatsUnreadCount
                            NeXoRaNavDestination.ALERTS -> alertsUnreadCount
                            else -> 0
                        }

                        val a11yDescription = buildString {
                            append(destination.title)
                            if (unreadCount > 0) {
                                append(", $unreadCount unread")
                            }
                        }

                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (!isSelected) {
                                    onDestinationSelected(destination)
                                }
                            },
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .scale(animatedScale)
                                        .semantics {
                                            role = Role.Tab
                                            selected = isSelected
                                            contentDescription = a11yDescription
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (unreadCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge(
                                                    containerColor = badgeContainerColor,
                                                    contentColor = badgeContentColor
                                                ) {
                                                    Text(
                                                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                                        fontSize = 9.sp,
                                                        lineHeight = 10.sp
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(id = destination.iconRes),
                                                contentDescription = null,
                                                tint = animatedIconColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            painter = painterResource(id = destination.iconRes),
                                            contentDescription = null,
                                            tint = animatedIconColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            },
                            label = null, // Icon-only: all text labels removed
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = nexoraGreen,
                                unselectedIconColor = unselectedIconColor,
                                selectedTextColor = Color.Transparent,
                                unselectedTextColor = Color.Transparent,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    }
}
}

/**
 * Convenient alias function matching various naming conventions.
 */
@Composable
fun NeXoRaBottomNavigation(
    selectedDestination: NeXoRaNavDestination,
    onDestinationSelected: (NeXoRaNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    chatsUnreadCount: Int = 0,
    alertsUnreadCount: Int = 0,
    isDark: Boolean = false
) {
    NeXoRaBottomNavigationBar(
        selectedDestination = selectedDestination,
        onDestinationSelected = onDestinationSelected,
        modifier = modifier,
        chatsUnreadCount = chatsUnreadCount,
        alertsUnreadCount = alertsUnreadCount,
        isDark = isDark
    )
}

@Preview(name = "Light Mode", showBackground = true)
@Composable
private fun NeXoRaBottomNavigationBarPreviewLight() {
    NeXoRaBottomNavigationBar(
        selectedDestination = NeXoRaNavDestination.HOME,
        onDestinationSelected = {},
        chatsUnreadCount = 3,
        alertsUnreadCount = 1,
        isDark = false
    )
}

@Preview(name = "Dark Mode", showBackground = true)
@Composable
private fun NeXoRaBottomNavigationBarPreviewDark() {
    NeXoRaBottomNavigationBar(
        selectedDestination = NeXoRaNavDestination.MAP,
        onDestinationSelected = {},
        chatsUnreadCount = 0,
        alertsUnreadCount = 2,
        isDark = true
    )
}
