package com.example.scamdetect.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Big, plain type for older users (the same sizes as iOS, see ios-app UI/Theme.swift): body text 20 sp, buttons
 * (labelLarge) 22 sp, titles 28 sp and up. Every size is in sp, so the phone's own font size still scales it, and
 * "Text size: Extra large" multiplies everything by 1.15 (ui/theme/Theme.kt). Screens scroll instead of shrinking.
 * Used for "Large" and "Extra large" (Simple mode's default).
 */
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

val SeniorTypography = Typography(
    displayLarge = style(48, 56),
    displayMedium = style(40, 48),
    displaySmall = style(36, 44),
    headlineLarge = style(34, 42, FontWeight.Bold),
    headlineMedium = style(30, 38, FontWeight.Bold),
    headlineSmall = style(28, 36, FontWeight.Bold),
    titleLarge = style(26, 34, FontWeight.SemiBold),
    titleMedium = style(22, 30, FontWeight.SemiBold),
    titleSmall = style(20, 28, FontWeight.SemiBold),
    bodyLarge = style(20, 30),
    bodyMedium = style(19, 28),
    bodySmall = style(18, 26),
    labelLarge = style(22, 28, FontWeight.SemiBold),
    labelMedium = style(18, 24, FontWeight.Medium),
    labelSmall = style(17, 22, FontWeight.Medium),
)

/** "Text size: Normal" (Standard mode's default): Material's own sizes, scaled by the phone's font size only. */
val StandardTypography = Typography()
