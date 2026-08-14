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
    val isMerged: Boolean = false,
    /**
     * Couleur de tracé lue dans le fichier importé (ARGB), ou null si le fichier n'en
     * portait pas — cas de tous les GPX, et des KML sans style de ligne.
     *
     * Nullable à dessein : il faut pouvoir distinguer « le fichier ne disait rien »
     * de « le fichier demandait du noir ». Sans cette distinction, un parcours sans
     * couleur se dessinerait en noir au lieu de reprendre celle de la palette.
     */
    val sourceColor: Int? = null
)
