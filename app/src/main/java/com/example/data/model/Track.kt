package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val activityType: String = "Randonnée", // Hiking, Cycling, Running, etc.
    val startTime: Long,
    val endTime: Long = 0,
    val totalDistance: Double = 0.0, // in meters
    val duration: Long = 0, // in seconds
    val maxSpeed: Double = 0.0, // in m/s
    val avgSpeed: Double = 0.0, // in m/s
    val elevationGain: Double = 0.0, // in meters
    val elevationLoss: Double = 0.0, // in meters
    val isRecording: Boolean = false,
    val isImported: Boolean = false,
    val isSelectedForMap: Boolean = false,
    val isMerged: Boolean = false
)
