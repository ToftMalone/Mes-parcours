package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["trackId"]),
        // Permet de restreindre rapidement une trace très dense à la zone visible
        // de la carte sans parcourir tous ses points.
        Index(value = ["trackId", "latitude"])
    ]
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0, // in meters
    val speed: Float = 0f, // in m/s
    val timestamp: Long,
    val isDiscontinuous: Boolean = false
)
