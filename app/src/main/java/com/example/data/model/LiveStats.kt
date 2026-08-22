package com.example.data.model

data class LiveStats(
    val durationSec: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0
)
