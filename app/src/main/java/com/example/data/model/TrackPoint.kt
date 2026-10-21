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
        // Allows quickly restricting a very dense track to the visible area
        // of the map without walking through all its points.
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
    val isDiscontinuous: Boolean = false,
    /**
     * Color of the segment this point belongs to (ARGB), or null if its original
     * file did not specify one.
     *
     * Carried by the point and not by the track: a single KML file exported from
     * Google Earth can gather dozens of trips, each with its own color. One
     * color per track would paint them all the same, which would mean losing
     * the very information we are trying to keep.
     */
    val segmentColor: Int? = null
)
