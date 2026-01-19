package com.example.cedokbooster

import android.util.Log
import java.net.*
import java.util.concurrent.*
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicInteger

class DnsQualityChecker {
    
    companion object {
        private const val TAG = "DnsQualityChecker"
        
        // Panda-specific domains untuk test
        private val PANDA_DOMAINS = listOf(
            "perseus-productanalytics.deliveryhero.net",
            "my.usehurrier.com",
            "service2.us.incognia.com",
            "api.mapbox.com",
            "deliveryhero.net"
        )
        
        // Standard domains untuk backup check
        private val STANDARD_DOMAINS = listOf(
            "google.com",
            "cloudflare.com",
            "whatsapp.com"
        )
        
        /**
         * Check kualiti DNS untuk Panda
         */
        fun checkDnsForPanda(dnsServer: String): DnsQualityResult {
            return try {
                val pandaResults = mutableListOf<Long>()
                val pandaSuccess = AtomicInteger(0)
                
                val standardResults = mutableListOf<Long>()
                val standardSuccess = AtomicInteger(0)
                
                val executor = Executors.newFixedThreadPool(3)
                val latch = CountDownLatch(PANDA_DOMAINS.size + STANDARD_DOMAINS.size)
                
                // Test Panda domains
                PANDA_DOMAINS.forEach { domain ->
                    executor.submit {
                        try {
                            val startTime = System.nanoTime()
                            val addresses = InetAddress.getAllByName(domain)
                            val latency = (System.nanoTime() - startTime) / 1_000_000
                            
                            if (addresses.isNotEmpty()) {
                                pandaSuccess.incrementAndGet()
                                pandaResults.add(latency)
                            }
                        } catch (e: Exception) {
                            // Fail silent
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                
                // Test Standard domains
                STANDARD_DOMAINS.forEach { domain ->
                    executor.submit {
                        try {
                            val startTime = System.nanoTime()
                            val addresses = InetAddress.getAllByName(domain)
                            val latency = (System.nanoTime() - startTime) / 1_000_000
                            
                            if (addresses.isNotEmpty()) {
                                standardSuccess.incrementAndGet()
                                standardResults.add(latency)
                            }
                        } catch (e: Exception) {
                            // Fail silent
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                
                executor.shutdown()
                latch.await(3, TimeUnit.SECONDS)
                
                calculatePandaScore(dnsServer, pandaResults, pandaSuccess.get(), 
                                   standardResults, standardSuccess.get())
                
            } catch (e: Exception) {
                Log.e(TAG, "DNS check error: ${e.message}")
                DnsQualityResult(
                    server = dnsServer,
                    score = 0,
                    avgLatencyMs = 999,
                    pandaScore = 0,
                    successRate = 0,
                    isUsable = false,
                    status = "FAILED"
                )
            }
        }
        
        private fun calculatePandaScore(
            dnsServer: String,
            pandaLatencies: List<Long>,
            pandaSuccess: Int,
            standardLatencies: List<Long>,
            standardSuccess: Int
        ): DnsQualityResult {
            // Panda domains performance
            val pandaSuccessRate = if (PANDA_DOMAINS.isNotEmpty()) {
                (pandaSuccess * 100) / PANDA_DOMAINS.size
            } else 0
            
            val pandaAvgLatency = if (pandaLatencies.isNotEmpty()) {
                pandaLatencies.average().toLong()
            } else 999
            
            // Standard domains
            val standardSuccessRate = if (STANDARD_DOMAINS.isNotEmpty()) {
                (standardSuccess * 100) / STANDARD_DOMAINS.size
            } else 0
            
            // Scoring: 70% Panda, 30% Standard
            val pandaScore = if (pandaSuccessRate >= 80 && pandaAvgLatency < 500) {
                (70 * (100 - minOf(pandaAvgLatency / 5, 100)) / 100).toInt()
            } else 0
            
            val standardScore = if (standardSuccessRate >= 50) {
                (30 * standardSuccessRate / 100)
            } else 0
            
            val totalScore = pandaScore + standardScore
            
            // Determine status
            val status = when {
                pandaSuccessRate == 100 && pandaAvgLatency < 200 -> "PANDA_EXCELLENT"
                pandaSuccessRate >= 80 && pandaAvgLatency < 400 -> "PANDA_GOOD"
                pandaSuccessRate >= 60 -> "PANDA_FAIR"
                pandaSuccessRate >= 40 -> "PANDA_WEAK"
                standardSuccessRate >= 80 -> "STANDARD_OK"
                else -> "UNUSABLE"
            }
            
            val isUsable = pandaSuccessRate >= 60 || standardSuccessRate >= 80
            
            return DnsQualityResult(
                server = dnsServer,
                score = totalScore.coerceIn(0, 100),
                avgLatencyMs = pandaAvgLatency,
                pandaScore = pandaSuccessRate,
                successRate = standardSuccessRate,
                isUsable = isUsable,
                status = status
            )
        }
        
        /**
         * Pilih DNS terbaik untuk Panda dari list candidates
         */
        fun selectBestDnsForPanda(): String {
            Log.d(TAG, "🔍 Selecting best DNS for Panda...")
            
            val candidates = listOf(
                "1.1.1.1",  // Cloudflare
                "1.0.0.1",  // Cloudflare secondary
                "8.8.8.8",  // Google
                "8.8.4.4",  // Google secondary
                "9.9.9.9",  // Quad9
                "208.67.222.222" // OpenDNS
            )
            
            val results = mutableListOf<DnsQualityResult>()
            val executor = Executors.newCachedThreadPool()
            
            candidates.forEach { dns ->
                executor.submit {
                    val result = checkDnsForPanda(dns)
                    synchronized(results) {
                        results.add(result)
                    }
                    Log.d(TAG, "DNS $dns → Score: ${result.score}, Status: ${result.status}")
                }
            }
            
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            
            // Pilih yang terbaik
            return results
                .filter { it.isUsable }
                .maxByOrNull { it.score }
                ?.server
                ?: "1.1.1.1" // Fallback ke Cloudflare
        }
        
        /**
         * Quick check untuk single DNS (fast mode)
         */
        fun quickDnsCheck(dnsServer: String): Boolean {
            return try {
                val start = System.nanoTime()
                InetAddress.getAllByName("google.com")
                val latency = (System.nanoTime() - start) / 1_000_000
                latency < 300
            } catch (e: Exception) {
                false
            }
        }
    }
    
    data class DnsQualityResult(
        val server: String,
        val score: Int,
        val avgLatencyMs: Long,
        val pandaScore: Int,
        val successRate: Int,
        val isUsable: Boolean,
        val status: String
    )
}
