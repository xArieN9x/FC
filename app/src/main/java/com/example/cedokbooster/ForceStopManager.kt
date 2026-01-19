package com.example.cedokbooster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi

class ForceStopManager(private val context: Context) {
    private val TAG = "ForceStopManager"
    
    // Variables untuk cleanup
    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null
    
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    
    companion object {
        private const val COMPANION_TAG = "ForceStopManager-Companion"
        
        /**
         * STATIC VERSION: Untuk dipanggil dari mana-mana (AppCoreEngService, etc.)
         */
        fun stopAllServices(context: Context) {
            Log.d(COMPANION_TAG, "🛑 stopAllServices (Static) called")
            
            // STEP 1: Hantar intent ke AppCoreEngService untuk stop GPS dulu
            val stopIntent = Intent(context, Class.forName("com.example.cedokbooster.AppCoreEngService")).apply {
                action = "STOP_GPS_AND_ENGINE"
            }
            
            try {
                // Start service untuk process stop command
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(stopIntent)
                } else {
                    context.startService(stopIntent)
                }
                Log.d(COMPANION_TAG, "Sent STOP command to AppCoreEngService")
            } catch (e: Exception) {
                Log.w(COMPANION_TAG, "Failed to send STOP command: ${e.message}")
            }
            
            // STEP 2: Stop VPN Service
            try {
                context.stopService(Intent(context, Class.forName("com.example.cedokbooster.VpnDnsService")))
                Log.d(COMPANION_TAG, "VPN service stop command sent")
            } catch (e: Exception) {
                Log.w(COMPANION_TAG, "VPN stop warning: ${e.message}")
            }
            
            // STEP 3: Tunggu sekejab, kemudian force stop kedua-dua services
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Force stop AppCoreEngService
                    context.stopService(Intent(context, Class.forName("com.example.cedokbooster.AppCoreEngService")))
                    Log.d(COMPANION_TAG, "AppCoreEngService force stopped")
                    
                    // Force stop VpnDnsService
                    context.stopService(Intent(context, Class.forName("com.example.cedokbooster.VpnDnsService")))
                    Log.d(COMPANION_TAG, "VpnDnsService force stopped")
                    
                } catch (e: Exception) {
                    Log.w(COMPANION_TAG, "Final service stop warning: ${e.message}")
                }
            }, 300)
        }
    }
    
    /**
     * NUCLEAR OPTION: Stop semua services, network, GPS dan kill app
     */
    fun stopEverythingNuclear() {
        Log.d(TAG, "🚀 NUCLEAR STOP SEQUENCE STARTED")
        
        try {
            // SEQUENCE 1: STOP ALL SERVICES
            stopAllServicesInternal() // Panggil internal version
            
            // SEQUENCE 2: NETWORK CLEANUP
            cleanupNetwork()
            
            // SEQUENCE 3: TRIGGER NETWORK REFRESH
            triggerNetworkReevaluation()
            
            // SEQUENCE 4: STOP LOCATION/GPS
            stopLocationServices()
            
            // SEQUENCE 5: REMOVE FROM RECENT APPS
            removeFromRecent()
            
            // SEQUENCE 6: NUCLEAR KILL (with delay)
            Handler(Looper.getMainLooper()).postDelayed({
                nuclearKillProcess()
            }, 300)
            
        } catch (e: Exception) {
            Log.e(TAG, "Nuclear stop error: ${e.message}")
            // Last resort kill
            Process.killProcess(Process.myPid())
        }
    }
    
    /**
     * INTERNAL VERSION: Untuk dipanggil dari dalam class ini sahaja
     */
    private fun stopAllServicesInternal() {
        Log.d(TAG, "1. Stopping all services (Internal)...")
        
        // Panggil static companion function (sama code tapi reuse)
        stopAllServices(context)
    }
    
    /**
     * 2. NETWORK CLEANUP
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun cleanupNetwork() {
        Log.d(TAG, "2. Cleaning up network...")
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Release network binding
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.bindProcessToNetwork(null)
        }
        
        // Unregister semua network callbacks
        networkCallbacks.forEach { callback ->
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Ignore jika sudah unregistered
            }
        }
        networkCallbacks.clear()
    }
    
    /**
     * 3. TRIGGER NETWORK RE-EVALUATION
     */
    private fun triggerNetworkReevaluation() {
        Log.d(TAG, "3. Triggering network re-evaluation...")
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Force fresh network request
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Fresh network available, forcing DNS lookup...")
                
                // Bind dan immediate release
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(network)
                    cm.bindProcessToNetwork(null)
                }
                
                // Force fresh DNS lookup
                forceFreshDnsLookup()
                
                // Unregister callback
                try {
                    cm.unregisterNetworkCallback(this)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable, unregistering callback")
                try {
                    cm.unregisterNetworkCallback(this)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        
        networkCallbacks.add(callback)
        
        // Request network dengan timeout pendek
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.requestNetwork(request, callback, 1500) // 1.5 second timeout
        }
    }
    
    /**
     * 3A. FORCE FRESH DNS LOOKUP
     */
    private fun forceFreshDnsLookup() {
        Thread {
            try {
                // Buka connection ke server popular yang pasti ada
                val url = java.net.URL("http://connectivitycheck.gstatic.com/generate_204")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false
                
                connection.connect()
                val responseCode = connection.responseCode
                Log.d(TAG, "DNS lookup triggered, response: $responseCode")
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "DNS lookup attempt completed (ignore errors)")
            }
        }.start()
    }
    
    /**
     * 4. STOP LOCATION SERVICES (STANDARD ANDROID LOCATION API)
     */
    private fun stopLocationServices() {
        Log.d(TAG, "4. Stopping location services...")
        
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Stop GPS listener jika ada
            gpsListener?.let {
                try {
                    lm.removeUpdates(it)
                    Log.d(TAG, "GPS listener removed")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove GPS listener: ${e.message}")
                }
            }
            
            // Stop network listener jika ada
            networkListener?.let {
                try {
                    lm.removeUpdates(it)
                    Log.d(TAG, "Network listener removed")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove network listener: ${e.message}")
                }
            }
            
            // Generic cleanup: Remove updates untuk semua providers
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            
            providers.forEach { provider ->
                try {
                    // Get semua active listeners untuk provider ini dan remove
                    lm.removeUpdates { true } // Remove semua listeners untuk app ini
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            Log.d(TAG, "Location services cleanup completed")
            
        } catch (e: Exception) {
            Log.w(TAG, "Location stop warning: ${e.message}")
        }
    }
    
    /**
     * 5. REMOVE FROM RECENT APPS
     */
    private fun removeFromRecent() {
        Log.d(TAG, "5. Removing from recent apps...")
        
        // Jika context adalah Activity
        if (context is android.app.Activity) {
            val activity = context as android.app.Activity
            
            // Untuk Android 5.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.finishAndRemoveTask()
            } else {
                activity.finishAffinity()
            }
            Log.d(TAG, "Activity removed from recent")
        }
    }
    
    /**
     * 6. NUCLEAR KILL PROCESS
     */
    private fun nuclearKillProcess() {
        Log.d(TAG, "6. NUCLEAR KILL PROCESS EXECUTED")
        
        try {
            // Kill background processes app sendiri
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(context.packageName)
            Log.d(TAG, "Background processes killed")
            
            // Kill main process
            Process.killProcess(Process.myPid())
            Log.d(TAG, "Main process killed")
            
            // Force exit
            System.exit(0)
            
        } catch (e: Exception) {
            Log.e(TAG, "Kill process error: ${e.message}")
            // Fallback
            Process.killProcess(Process.myPid())
        }
    }
    
    /**
     * SETTER untuk location listeners (call dari MainActivity)
     */
    fun setLocationListeners(
        gpsListener: LocationListener?,
        networkListener: LocationListener?
    ) {
        this.gpsListener = gpsListener
        this.networkListener = networkListener
    }
}
