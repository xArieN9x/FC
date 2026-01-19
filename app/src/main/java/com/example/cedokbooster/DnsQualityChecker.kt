package com.example.cedokbooster

import android.util.Log
import java.net.*
import java.util.concurrent.*
import kotlin.math.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class DnsQualityChecker {

    companion object {
        private const val TAG = "DnsQualityChecker"

        private val PANDA_DOMAINS = listOf(
            "perseus-productanalytics.deliveryhero.net",
            "my.usehurrier.com",
            "service2.us.incognia.com",
            "api.mapbox.com",
            "deliveryhero.net"
        )

        private val STANDARD_DOMAINS = listOf(
            "google.com",
            "cloudflare.com",
            "whatsapp.com"
        )

        // ===== MEMORY: simpan sejarah =====
        private val dnsHistory = ConcurrentHashMap<String, MutableList<Long>>()
        private val dnsFailHistory = ConcurrentHashMap<String, AtomicInteger>()
        private const val MAX_HISTORY = 30

        fun checkDnsForPanda(dnsServer: String): DnsQualityResult {
            return try {
                val pandaResults = mutableListOf<Long>()
                val pandaSuccess = AtomicInteger(0)
                val standardResults = mutableListOf<Long>()
                val standardSuccess = AtomicInteger(0)

                val executor = Executors.newFixedThreadPool(3)
                val latch = CountDownLatch(PANDA_DOMAINS.size + STANDARD_DOMAINS.size)

                PANDA_DOMAINS.forEach { domain ->
                    executor.submit {
                        try {
                            val startTime = System.nanoTime()
                            InetAddress.getAllByName(domain)
                            val latency = (System.nanoTime() - startTime) / 1_000_000
                            pandaResults.add(latency)
                            pandaSuccess.incrementAndGet()
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                STANDARD_DOMAINS.forEach { domain ->
                    executor.submit {
                        try {
                            val startTime = System.nanoTime()
                            InetAddress.getAllByName(domain)
                            val latency = (System.nanoTime() - startTime) / 1_000_000
                            standardResults.add(latency)
                            standardSuccess.incrementAndGet()
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                executor.shutdown()
                latch.await(3, TimeUnit.SECONDS)

                val result = calculatePandaScore(
                    dnsServer,
                    pandaResults,
                    pandaSuccess.get(),
                    standardResults,
                    standardSuccess.get()
                )

                updateHistory(dnsServer, result.avgLatencyMs, result.isUsable)
                result

            } catch (e: Exception) {
                Log.e(TAG, "DNS check error: ${e.message}")
                updateHistory(dnsServer, 999, false)
                DnsQualityResult(dnsServer, 0, 999, 0, 0, false, "FAILED")
            }
        }

        private fun updateHistory(dns: String, latency: Long, ok: Boolean) {
            val list = dnsHistory.getOrPut(dns) { mutableListOf() }
            list.add(latency)
            if (list.size > MAX_HISTORY) list.removeAt(0)

            if (!ok) {
                dnsFailHistory.getOrPut(dns) { AtomicInteger(0) }.incrementAndGet()
            }
        }

        private fun getTrendScore(dns: String): Int {
            val list = dnsHistory[dns] ?: return 50
            if (list.isEmpty()) return 50

            val avg = list.average()
            val variance = list.map { (it - avg).pow(2) }.average()
            val jitter = sqrt(variance)

            return when {
                avg < 150 && jitter < 50 -> 100
                avg < 300 && jitter < 100 -> 80
                avg < 500 -> 60
                else -> 30
            }
        }

        private fun calculatePandaScore(
            dnsServer: String,
            pandaLatencies: List<Long>,
            pandaSuccess: Int,
            standardLatencies: List<Long>,
            standardSuccess: Int
        ): DnsQualityResult {

            val pandaSuccessRate = (pandaSuccess * 100) / PANDA_DOMAINS.size
            val pandaAvgLatency = if (pandaLatencies.isNotEmpty()) pandaLatencies.average().toLong() else 999
            val standardSuccessRate = (standardSuccess * 100) / STANDARD_DOMAINS.size

            val instantScore = when {
                pandaSuccessRate >= 80 && pandaAvgLatency < 200 -> 80
                pandaSuccessRate >= 60 && pandaAvgLatency < 400 -> 60
                pandaSuccessRate >= 40 -> 40
                else -> 10
            }

            val trendScore = getTrendScore(dnsServer)
            val failPenalty = dnsFailHistory[dnsServer]?.get() ?: 0

            var totalScore = (instantScore * 0.6 + trendScore * 0.4).toInt()
            totalScore -= min(failPenalty * 5, 30)

            val status = when {
                totalScore >= 85 -> "PANDA_ELITE"
                totalScore >= 70 -> "PANDA_STRONG"
                totalScore >= 50 -> "PANDA_OK"
                totalScore >= 30 -> "PANDA_WEAK"
                else -> "UNSTABLE"
            }

            val usable = totalScore >= 40 || standardSuccessRate >= 80

            return DnsQualityResult(
                server = dnsServer,
                score = totalScore.coerceIn(0, 100),
                avgLatencyMs = pandaAvgLatency,
                pandaScore = pandaSuccessRate,
                successRate = standardSuccessRate,
                isUsable = usable,
                status = status
            )
        }

        fun selectBestDnsForPanda(): String {
            Log.d(TAG, "🔍 Selecting best DNS for Panda...")

            val candidates = listOf(
                "1.1.1.1", "1.0.0.1",
                "8.8.8.8", "8.8.4.4",
                "9.9.9.9",
                "208.67.222.222"
            )

            val results = mutableListOf<DnsQualityResult>()
            val executor = Executors.newCachedThreadPool()

            candidates.forEach { dns ->
                executor.submit {
                    val result = checkDnsForPanda(dns)
                    synchronized(results) {
                        results.add(result)
                    }
                    Log.d(TAG, "DNS $dns → ${result.score} (${result.status})")
                }
            }

            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)

            return results
                .filter { it.isUsable }
                .sortedByDescending { it.score }
                .firstOrNull()
                ?.server
                ?: "1.1.1.1"
        }

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
