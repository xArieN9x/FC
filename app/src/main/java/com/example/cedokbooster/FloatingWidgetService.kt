package com.example.cedokbooster

import android.os.Handler
import android.os.Looper
import java.util.Timer
import java.util.TimerTask

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.content.ContextCompat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import android.app.ActivityManager
import android.net.TrafficStats
import java.io.BufferedReader
import java.io.FileReader

// TAMBAHAN UNTUK COROUTINES
import kotlinx.coroutines.*

// TAMBAHAN UNTUK NETWORK
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.DatagramPacket

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isActive = false
    
    private val handler = Handler(Looper.getMainLooper())
    private var monitorTimer: Timer? = null

    companion object {
        private const val TAG = "FloatingWidgetService"
        const val ACTION_START_WIDGET = "com.example.cedokbooster.START_WIDGET"
        const val ACTION_STOP_WIDGET = "com.example.cedokbooster.STOP_WIDGET"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingWidgetService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WIDGET -> {
                isActive = intent.getBooleanExtra("isActive", false)
                if (floatingView == null) {
                    showFloatingWidget()
                } else {
                    updateWidgetColor()
                }
            }
            ACTION_STOP_WIDGET -> {
                stopMonitor()
                removeFloatingWidget()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showFloatingWidget() {
        if (floatingView != null) return
    
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.widget_layout, null)
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        
        // UPDATE SIZE ikut XML (130dp)
        val widgetSize = dpToPx(130)
        
        val params = WindowManager.LayoutParams(
            widgetSize,
            widgetSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - widgetSize
            y = dpToPx(150)
        }
    
        windowManager?.addView(floatingView, params)
        updateWidgetColor()
        setupTouchListener(params)
        startMonitor()
        
        Log.d(TAG, "Floating widget shown (size: ${widgetSize}px)")
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    private fun updateWidgetColor() {
        floatingView?.findViewById<TextView>(R.id.tvWidget)?.apply {
            // Text simple dulu
            text = if (isActive) "VPN: ✓\nSTATUS: ON" else "VPN: ✗\nSTATUS: OFF"
            
            // Guna drawable LAMA yang confirm work
            background = ContextCompat.getDrawable(
                this@FloatingWidgetService,
                if (isActive) R.drawable.widget_bg_green else R.drawable.widget_bg
            )
        }
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        openMainActivity()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Log.d(TAG, "Main activity opened from widget")
    }

    private fun removeFloatingWidget() {
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
            Log.d(TAG, "Floating widget removed")
        }
    }

    private fun startMonitor() {
        Log.d(TAG, "Monitor started")
        
        monitorTimer = Timer()
        monitorTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                updateWidgetInfo()
            }
        }, 1000, 2000) // Start after 1s, update every 2s
    }
    
    private fun stopMonitor() {
        Log.d(TAG, "Monitor stopped")
        monitorTimer?.cancel()
        monitorTimer = null
    }

    private var lastSpeedTest: Long = 0
    private var lastSpeedValue: Long = 0L

    private fun updateWidgetInfo() {
        // Guna CoroutineScope instead of Thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ramUsage = getRamUsage()
                val cpuUsage = getCpuUsage()
                
                // LIGHT: Ping-based estimation (setiap 30 saat)
                val currentTime = System.currentTimeMillis()
                val speedKbps = if (currentTime - lastSpeedTest > 30000) {
                    // Panggil suspend function directly dalam coroutine
                    val estimatedSpeed = estimateBandwidth()
                    lastSpeedTest = currentTime
                    estimatedSpeed
                } else {
                    // Kekalkan last reading
                    lastSpeedValue
                }
                
                lastSpeedValue = speedKbps
                
                Log.d(TAG, "Bandwidth: ${speedKbps}Kbps (${speedKbps / 1000}Mbps)")
                
                val speedText = when {
                    speedKbps >= 10000 -> "${speedKbps / 1000}M"
                    speedKbps >= 1000 -> "${speedKbps / 1000}M"
                    speedKbps >= 100 -> "${speedKbps}K"
                    speedKbps > 0 -> "<100K"
                    else -> "0K"
                }
                
                val statusText = if (isActive) {
                    """
                    VPN: ✓
                    RAM: ${ramUsage}%
                    CPU: ${cpuUsage}%
                    NET: $speedText
                    """.trimIndent()
                } else {
                    """
                    VPN: ✗
                    RAM: ${ramUsage}%
                    CPU: ${cpuUsage}%
                    NET: $speedText
                    """.trimIndent()
                }
                
                // Update UI on Main thread
                withContext(Dispatchers.Main) {
                    floatingView?.findViewById<TextView>(R.id.tvWidget)?.apply {
                        text = statusText
                        
                        // FIX: Reset semua text ke BLACK dulu
                        setTextColor(ContextCompat.getColor(this@FloatingWidgetService, android.R.color.black))
                        
                        // Apply RED hanya untuk NET jika speed < 1Mbps
                        if (speedKbps < 1000 && speedKbps > 0) {
                            // Cari position "NET:" dan apply color merah
                            val fullText = text.toString()
                            val netIndex = fullText.indexOf("NET:")
                            
                            if (netIndex != -1) {
                                val spannable = android.text.SpannableString(fullText)
                                spannable.setSpan(
                                    android.text.style.ForegroundColorSpan(
                                        ContextCompat.getColor(this@FloatingWidgetService, android.R.color.holo_red_dark)
                                    ),
                                    netIndex,
                                    fullText.length,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                text = spannable
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update error: ${e.message}")
            }
        }
    }

    private fun TextView.applyColorToNetLine(colorResId: Int) {
        val fullText = this.text.toString()
        val lines = fullText.split("\n")
        
        if (lines.size >= 4) {
            val coloredText = buildString {
                // Line 1-3 (VPN, RAM, CPU) - default color
                append(lines[0])
                append("\n")
                append(lines[1])
                append("\n")
                append(lines[2])
                append("\n")
                
                // Line 4 (NET) - dengan color
                append("<font color='${getColorCode(colorResId)}'>")
                append(lines[3])
                append("</font>")
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                this.text = android.text.Html.fromHtml(coloredText, android.text.Html.FROM_HTML_MODE_LEGACY)
            } else {
                this.text = android.text.Html.fromHtml(coloredText)
            }
        }
    }
    
    private fun getColorCode(colorResId: Int): String {
        return when (colorResId) {
            android.R.color.black -> "#000000"
            android.R.color.holo_red_dark -> "#D32F2F"
            else -> "#000000"
        }
    }

    private fun getRamUsage(): Int {
        return try {
            val activityManager = getSystemService(android.app.ActivityManager::class.java)
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val usedMemory = memoryInfo.totalMem - memoryInfo.availMem
            val percentage = (usedMemory * 100 / memoryInfo.totalMem).toInt()
            percentage.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e(TAG, "RAM error: ${e.message}")
            0
        }
    }
    
    private fun getCpuUsage(): Int {
        return try {
            // Method 1: Guna /proc/stat dengan permission READ_PROCESS_STATE
            calculateRealCpuUsage()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for CPU read")
            // Fallback ke logical estimation
            getLogicalCpuEstimation()
        } catch (e: Exception) {
            Log.e(TAG, "CPU read error: ${e.message}")
            getLogicalCpuEstimation()
        }
    }

    private fun calculateRealCpuUsage(): Int {
        val reader = BufferedReader(FileReader("/proc/stat"))
        val line = reader.readLine()
        reader.close()
        
        if (!line.startsWith("cpu ")) return 0
        
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 9) return 0
        
        // CPU calculation formula
        val user = parts[1].toLong()
        val nice = parts[2].toLong()
        val system = parts[3].toLong()
        val idle = parts[4].toLong()
        val ioWait = parts[5].toLong()
        val irq = parts[6].toLong()
        val softIrq = parts[7].toLong()
        val steal = if (parts.size > 8) parts[8].toLong() else 0
        
        val totalCpuTime = user + nice + system + idle + ioWait + irq + softIrq + steal
        val idleTime = idle + ioWait
        
        // Untuk simple calculation, return estimation
        val usagePercent = if (totalCpuTime > 0) {
            ((totalCpuTime - idleTime) * 100 / totalCpuTime).toInt()
        } else {
            0
        }
        
        // Ambil system CPU usage, bukan process specific
        return usagePercent.coerceIn(0, 100)
    }
    
    private fun getLogicalCpuEstimation(): Int {
        // Fallback logic
        return when {
            VpnDnsService.isVpnRunning() -> 35
            isActive -> 25
            else -> 8
        }
    }

    private suspend fun estimateBandwidth(): Long {
        return withContext(Dispatchers.IO) {
            try {
                // Test connection quality dengan ping
                val targets = listOf(
                    "https://1.1.1.1/cdn-cgi/trace",
                    "https://www.google.com/generate_204",
                    "https://connectivitycheck.gstatic.com/generate_204"
                )
                
                var totalPing = 0L
                var successCount = 0
                
                for (target in targets.take(2)) { // Test 2 sahaja
                    try {
                        val start = System.currentTimeMillis()
                        java.net.URL(target).openConnection().apply {
                            connectTimeout = 2000
                            readTimeout = 2000
                            connect()
                            getInputStream().close()
                        }
                        val ping = System.currentTimeMillis() - start
                        totalPing += ping
                        successCount++
                    } catch (e: Exception) {
                        // Skip failed
                    }
                }
                
                val avgPing = if (successCount > 0) totalPing / successCount else 1000
                
                // Estimate speed dari ping (rough estimation)
                return@withContext when {
                    avgPing < 50 -> 30000L   // 30Mbps (excellent)
                    avgPing < 100 -> 15000L  // 15Mbps (good)
                    avgPing < 200 -> 5000L   // 5Mbps (average)
                    avgPing < 500 -> 1000L   // 1Mbps (poor)
                    else -> 100L             // 100Kbps (very poor)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Bandwidth estimate error: ${e.message}")
                0L
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitor()
        removeFloatingWidget()
        Log.d(TAG, "FloatingWidgetService destroyed")
    }
}
