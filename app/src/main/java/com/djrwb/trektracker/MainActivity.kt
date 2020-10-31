package com.djrwb.trektracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import com.djrwb.trektracker.databinding.MainFragmentBinding
import com.djrwb.trektracker.ui.main.MainFragment
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private var mGPSBound = false
    private lateinit var mGPSService: GPSService
    private val mGPSIntent: Intent by lazy {
        Intent(applicationContext, GPSService::class.java)
    }

    private val mGPSConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GPSService.GPSBinder
            mGPSService = binder.getService()
            mGPSBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.e("GPSService was suddenly disconnected. Did it crash?")
            mGPSBound = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, MainFragment.newInstance())
                    .commitNow()
        }
        Timber.plant(Timber.DebugTree())
        Timber.i("Checking permissions...")
        if (
            ActivityCompat.checkSelfPermission(this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this@MainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Timber.i("Didn't have permissions. Requesting...")
            ActivityCompat.requestPermissions(this@MainActivity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.INTERNET
                ),1
            )
        }
        startService(mGPSIntent)
        //bindService(mGPSIntent, mGPSConnection, Context.BIND_AUTO_CREATE)
    }
    override fun onDestroy(){
        if(mGPSBound)
            unbindService(mGPSConnection)
        mGPSBound = false
        super.onDestroy()
    }
}