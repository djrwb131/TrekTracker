package com.djrwb.trektracker

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import timber.log.Timber


// TODO: There are about 6 places where permissions and GPS-enabled are checked - make this one place
// TODO: Why isn't Room saving anything?

class GPSService: Service() {
    // Private variables
    private val mLocationManager: LocationManager by lazy {
        applicationContext.getSystemService(AppCompatActivity.LOCATION_SERVICE) as LocationManager
    }
    private val mDatabaseThread = HandlerThread("mDatabaseThread")
    private var mDatabaseRunning = false
    private val mNotificationID = 12345
    private val mBinder = GPSBinder()
    private lateinit var mDatabase: LocationDatabase
    private var mStartID: Int = -1
    private val mLocationListener = LocationListener { loc ->
        saveLocation(loc)
    }

    private var mTripID = MutableLiveData<Int>(0)
    val tripID: LiveData<Int>
        get() { return mTripID }

    var mTripActive = false
        set(active) {
            if(active == mTripActive) return
            if(!active){
                mTripSavedTime += System.currentTimeMillis() - mTripActiveSince
            } else {
                if(mTripSavedTime == 0L) saveThisLocation()
                mTripActiveSince = System.currentTimeMillis()
            }
            setLocationUpdates(active)
            updateNotification()
            field = active
        }

    // mTripActiveSince - UTC time of last resume in milliseconds
    private var mTripActiveSince = 0L
    // mTripSavedTime - total elapsed time before the most recent pause
    private var mTripSavedTime = 0L
    val tripElapsedTime: Long
        get() {
            if(mTripActive)
                return mTripSavedTime + System.currentTimeMillis() - mTripActiveSince
            return mTripSavedTime
        }

    private var mTripDistance = 0.0
    val tripDistance: Double
        get() { return mTripDistance }

    private var mCurrentLocation = MutableLiveData<Location>()
    val currentLocation: LiveData<Location>
        get() { return mCurrentLocation }

    private var mTripTopSpeed = 0.0
    val tripTopSpeed: Double
        get() { return mTripTopSpeed }


    // Convenience functions
    fun newTrip() {
        val old_id = mTripID.value
        mTripID.value = (old_id?:0) + 1
        resetTrip()
    }
    fun resetTrip() {
        Handler(mDatabaseThread.looper).post( Runnable() {
            mDatabase.locDao().deleteTrip(mTripID.value!!)
        })
        mTripActive = false
        mTripSavedTime = 0
        updateNotification()
    }
    fun getLocationHistory(pTripID: Int, liveData: MutableLiveData<List<LocationData>>) {
        Handler(mDatabaseThread.looper).post( Runnable() {
            liveData.postValue(mDatabase.locDao().getAllFromTrip(pTripID))
        })
    }

    // Private functions
    private fun setLocationUpdates(enable: Boolean) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Timber.e("Couldn't enable location listener - ACCESS_FINE_LOCATION was denied.")
            return
        }
        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(applicationContext, "Please enable GPS!", Toast.LENGTH_SHORT)
            Timber.e("Couldn't enable location listener - GPS isn't enabled.")
            return
        }
        if(enable) {
            mLocationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000,
                0.0F,
                mLocationListener
            )
        } else {
            mLocationManager.removeUpdates(mLocationListener)
        }
    }
    private fun saveThisLocation(){
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Timber.e("Couldn't get a location - ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION was denied.")
            return
        }
        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this,"Please enable GPS!",Toast.LENGTH_SHORT)
            Timber.e("Couldn't enable location listener - GPS isn't enabled.")
            return
        }
        val l = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if(l == null) {
            Timber.e("location manager returned no location info :(")
        } else { saveLocation(l) }
    }
    private fun saveLocation(l: Location) {
        Handler(mDatabaseThread.looper).post( Runnable() {
            Timber.i("Saving location: $l")
            mDatabase.locDao().insert(LocationData(l, mTripID.value?:0))
        })
        val lastLocation = mCurrentLocation.value
        lastLocation?.let {
            mTripDistance += l.distanceTo(it)
        }
        mCurrentLocation.value = l
    }
    private fun getCurrentNotification(): Notification {
        var pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let {
                notificationIntent -> PendingIntent.getActivity(this,0,notificationIntent,0)
        }
        var trip_text = if(mTripActive) "active" else "paused"
        return NotificationCompat.Builder(this)
            .setContentTitle("TrekTracker")
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentText("Trip %d is %s.".format(mTripID.value?:0,trip_text))
            .setContentIntent(pendingIntent)
            .build()
    }
    private fun updateNotification() {
        with(NotificationManagerCompat.from(applicationContext)) {
            notify(12345,getCurrentNotification())
        }
    }
    private fun ensureThreadsAreStarted() {
        if(!mDatabaseThread.isAlive) mDatabaseThread.start()
    }
    private fun ensureDatabaseIsOpen() {
        if(this::mDatabase.isInitialized && mDatabase.isOpen) return
        if(!mDatabaseRunning) {
            mDatabaseRunning = true
            var mDatabaseHandler = Handler(mDatabaseThread.looper)
            mDatabaseHandler.post(Runnable() {
                Timber.i("Building database...")
                mDatabaseRunning = true
                mDatabase = Room.databaseBuilder(
                    this,
                    LocationDatabase::class.java,
                    "location-db"
                ).build()
                Timber.i("Database built.")
                mTripID.postValue(mDatabase.locDao().getLastTripID())
            })
        }
    }

    // Overrides
    override fun onCreate() {
        ensureThreadsAreStarted()
        ensureDatabaseIsOpen()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(mStartID == startId) {
            Timber.w("Received intent with startID %d; we've already started!".format(startId))
            return super.onStartCommand(intent, flags, startId)
        }
        mStartID = startId
        ensureThreadsAreStarted()
        ensureDatabaseIsOpen()
        startForeground(12345, getCurrentNotification())
        return Service.START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder {
        return mBinder
    }
    override fun onDestroy() {
        mDatabaseThread.quitSafely()
        mLocationManager.removeUpdates(mLocationListener)
        stopForeground(true)
    }
    inner class GPSBinder : Binder() {
        fun getService(): GPSService = this@GPSService
    }
}