package com.djrwb.trektracker

import androidx.room.*

@Database(entities = arrayOf(LocationData::class), version = 2)
abstract class LocationDatabase: RoomDatabase() {
    abstract fun locDao(): LocationHistoryDao
}

@Dao
interface LocationHistoryDao {
    @Query("SELECT * FROM location_history")
    fun getAll(): List<LocationData>

    @Query("SELECT * FROM location_history WHERE time > :since")
    fun getSince(since: Long): List<LocationData>

    @Insert
    fun insertAll(vararg locations: LocationData): List<Long>

    @Insert
    fun insert(location: LocationData): Long

    @Query("SELECT MAX(trip_id) FROM location_history")
    fun getLastTripID(): Int

    @Query("DELETE FROM location_history WHERE trip_id == :trip_id")
    fun deleteTrip(trip_id: Int)

    @Query("SELECT * FROM location_history WHERE trip_id == :trip_id ORDER BY time")
    fun getAllFromTrip(trip_id: Int): List<LocationData>
}

@Entity(tableName = "location_history")
data class LocationData(
    @PrimaryKey(autoGenerate = true) val r_id: Long,
    var time: Long,
    var latitude: Double,
    var longitude: Double,
    var speed: Float,
    var altitude: Double,
    var bearing: Float,
    var trip_id: Int
) {
    constructor(l: android.location.Location, trip_id: Int = 0)
            : this(0,l.time,l.latitude,l.longitude,l.speed,l.altitude,l.bearing,trip_id)
}
// TODO: Trip details + description for trip IDs in its own table