package com.example.scamdetect.ui.theme

import androidx.compose.ui.graphics.Color

// The ScamGuard purple, the same as the iOS app (ios-app/ScamGuard/UI/Theme.swift `brand`): #6650A4 in light mode,
// #D0BCFF in dark mode. The other roles are Material 3's baseline purple palette built around it, so buttons, the
// navigation bar, switches, progress indicators, text fields and selected states are all purple.
// Risk colours (SAFE green, UNSURE yellow, SCAM red, grey) are not here: they live in ui/InCallScreen.kt and are
// the same on iOS.

// ---- light
val Purple40 = Color(0xFF6650A4)          // primary (= iOS brand, light)
val OnPurple40 = Color(0xFFFFFFFF)
val PurpleContainer90 = Color(0xFFEADDFF)
val OnPurpleContainer10 = Color(0xFF21005D)
val PurpleGrey40 = Color(0xFF625B71)      // secondary
val PurpleGreyContainer90 = Color(0xFFE8DEF8)
val OnPurpleGreyContainer10 = Color(0xFF1D192B)
val Pink40 = Color(0xFF7D5260)            // tertiary
val PinkContainer90 = Color(0xFFFFD8E4)
val OnPinkContainer10 = Color(0xFF31111D)
val NeutralLight99 = Color(0xFFFFFBFE)    // background / surface
val NeutralLight10 = Color(0xFF1C1B1F)    // text on background / surface
val NeutralVariantLight90 = Color(0xFFE7E0EC)
val NeutralVariantLight30 = Color(0xFF49454F)
val OutlineLight = Color(0xFF79747E)
val OutlineVariantLight = Color(0xFFCAC4D0)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F2FA)
val SurfaceContainerLight = Color(0xFFF3EDF7)
val SurfaceContainerHighLight = Color(0xFFECE6F0)
val SurfaceContainerHighestLight = Color(0xFFE6E0E9)
val SurfaceDimLight = Color(0xFFDED8E1)
val SurfaceBrightLight = Color(0xFFFEF7FF)
val ErrorLight = Color(0xFFB3261E)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

// ---- dark
val Purple80 = Color(0xFFD0BCFF)          // primary (= iOS brand, dark)
val OnPurple80 = Color(0xFF381E72)
val PurpleContainer30 = Color(0xFF4F378B)
val OnPurpleContainer90 = Color(0xFFEADDFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val OnPurpleGrey80 = Color(0xFF332D41)
val PurpleGreyContainer30 = Color(0xFF4A4458)
val Pink80 = Color(0xFFEFB8C8)
val OnPink80 = Color(0xFF492532)
val PinkContainer30 = Color(0xFF633B48)
val NeutralDark6 = Color(0xFF141218)      // background / surface
val NeutralDark90 = Color(0xFFE6E0E9)     // text on background / surface
val NeutralVariantDark30 = Color(0xFF49454F)
val NeutralVariantDark80 = Color(0xFFCAC4D0)
val OutlineDark = Color(0xFF938F99)
val SurfaceContainerLowestDark = Color(0xFF0F0D13)
val SurfaceContainerLowDark = Color(0xFF1D1B20)
val SurfaceContainerDark = Color(0xFF211F26)
val SurfaceContainerHighDark = Color(0xFF2B2930)
val SurfaceContainerHighestDark = Color(0xFF36343B)
val SurfaceBrightDark = Color(0xFF3B383E)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

// ---- inverse (snackbars)
val InverseSurfaceLight = Color(0xFF313033)
val InverseOnSurfaceLight = Color(0xFFF4EFF4)
