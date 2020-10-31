package com.djrwb.trektracker.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.*
import androidx.lifecycle.ViewModelProvider
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.djrwb.trektracker.CloudService
import com.djrwb.trektracker.GPSService
import com.djrwb.trektracker.LocationData
import com.djrwb.trektracker.R
import com.djrwb.trektracker.databinding.MainActivityBinding
import com.djrwb.trektracker.databinding.MainFragmentBinding
import timber.log.Timber
import java.lang.Double.NaN

class MainFragment : Fragment() {
    private var mGPSBound = false
    private lateinit var mGPSService: GPSService
    private val mGPSIntent: Intent by lazy {
        Intent(activity, GPSService::class.java)
    }

    private var mCloudBound = false
    private lateinit var mCloudService: CloudService
    private val mCloudIntent: Intent by lazy {
        Intent(activity, CloudService::class.java)
    }

    private val mUITimerHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }
    private lateinit var mUITimerRunnable: Runnable
    private fun setTimerUpdates(update: Boolean) {
        mUITimerHandler.removeCallbacks(mUITimerRunnable)
        if(update){
            mUITimerHandler.post(mUITimerRunnable)
        }
    }
    private fun setTripText(){
        var avg = mGPSService.tripDistance / mGPSService.tripElapsedTime
        if(avg.isNaN())
            binding.averageSpeedLabel.text = "Average speed:\n0.0"
        else
            binding.averageSpeedLabel.text = "Average speed:\n" + avg.toString()
        binding.tripDistanceLabel.text = "Trip distance:\n" + mGPSService.tripDistance.toString()
        binding.topSpeedLabel.text = "Top speed:\n" + mGPSService.tripTopSpeed.toString()

    }
    private fun setTimerText(){
        var millis: Long = mGPSService.tripElapsedTime
        var seconds: Long = (millis/1000)%60
        var minutes: Long = (millis/(60*1000))%60
        var hours: Long = millis/(60*60*1000)
        // We don't need millisecond granularity - and with 100ms UI updates, we don't have it.
        // Use tenths of a second instead
        binding.tripTimeLabel.text = "Trip time:\n%02d:%02d:%02d.%01d".format(hours,minutes,seconds,(millis/100)%10)
    }
    private fun setSpeedText(it: Location?){
        binding.currentSpeedLabel.text = (it?.speed?:0*3.6).toString() + " KM/H"
    }
    private val mCloudConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CloudService.CloudBinder
            mCloudService = binder.getService()
            mCloudBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.e("CloudService was suddenly disconnected. Did it crash?")
            mCloudBound = false
        }
    }
    private val mGPSConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GPSService.GPSBinder
            mGPSService = binder.getService()
            mGPSBound = true
            mUITimerRunnable = Runnable() {
                setTimerText()
                mUITimerHandler.postDelayed(mUITimerRunnable,100)
            }

            // Do UI Initialization
            setTripText()
            setSpeedText(null)
            setTimerText()
            setTimerUpdates(mGPSService.mTripActive)
            binding.currentSpeedLabel.text = "0.0 KM/H"

            // Set up callbacks for UI updates
            mGPSService.currentLocation.observe(this@MainFragment, Observer { it: Location? ->
                setSpeedText(it)
                setTripText()
            })

            mGPSService.tripID.observe( this@MainFragment, Observer {
                binding.tripNumberLabel.text = it.toString()
            })

            // Debug stuff
            val locationHistory = MutableLiveData<List<LocationData>>()
            locationHistory.observe(this@MainFragment, Observer {
                var count = it.size
                if(it.size == 0 && !mGPSService.mTripActive) binding.startStopButton.text = "Start"
                binding.dataPointsButton.text = "Data points: $count"
                var temp: String = ""
                var subList = if(it.size < 5) it.asReversed() else it.asReversed().subList(0,5)
                subList.listIterator().forEach {
                    temp += "${it.time}: ${it.latitude},${it.longitude} @ ${it.speed}m/s\n"
                }
                if(mCloudBound) {
                    mCloudService.setPassword("ASDF".toCharArray())
                    mCloudService.sendToCloud(it)
                } else {
                    Timber.w("CloudService not bound. Trying to remedy...")
                    (activity ?: context)?.bindService(mCloudIntent, mCloudConnection, Context.BIND_AUTO_CREATE)
                }
                binding.locationHistoryLabel.text = temp
            })
            binding.dataPointsButton.setOnClickListener {
                mGPSService.getLocationHistory(mGPSService.tripID.value?:0,locationHistory)
            }
            if(mGPSService.mTripActive) binding.startStopButton.text = "Pause"
            else binding.startStopButton.text = "Resume"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.e("GPSService was suddenly disconnected. Did it crash?")
            mGPSBound = false
        }
    }
    companion object {
        fun newInstance() = MainFragment()
    }

    private lateinit var binding: MainFragmentBinding

    fun doBindService(){
        val bindingContext = activity ?: context
        if(!mGPSBound)
            bindingContext?.bindService(mGPSIntent, mGPSConnection, Context.BIND_AUTO_CREATE)
        if(!mCloudBound)
            bindingContext?.bindService(mCloudIntent, mCloudConnection, Context.BIND_AUTO_CREATE)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        doBindService()
        binding = MainFragmentBinding.inflate(inflater, container, false)
        binding.newTripButton.setOnClickListener {
            mGPSService.newTrip()
            setTripText()
            setSpeedText(null)
            setTimerText()
            setTimerUpdates(mGPSService.mTripActive)
            binding.startStopButton.text = "Start"
        }
        binding.resetTripButton.setOnClickListener {
            mGPSService.resetTrip()
            setTripText()
            setSpeedText(null)
            setTimerText()
            setTimerUpdates(mGPSService.mTripActive)
            binding.startStopButton.text = "Start"
        }
        binding.startStopButton.setOnClickListener {
            if(mGPSService.mTripActive) {
                mGPSService.mTripActive = false
                binding.startStopButton.text = "Resume"
            } else {
                mGPSService.mTripActive = true
                binding.startStopButton.text = "Pause"
            }
            setTimerUpdates(mGPSService.mTripActive)
        }
        binding.optionsButton.setOnClickListener {
            Toast.makeText(this.context,"No options yet :(",Toast.LENGTH_SHORT)
        }
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }
}