package com.example.shieldshare.managers.network

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * ARP Reader Implementation using /proc/net/arp
 * Based on the CSV specification (ProcArpReaderImpl)
 */
class ProcArpReaderImpl : IArpReader {
    companion object {
        private const val TAG = "ProcArpReaderImpl"
        private const val ARP_FILE_PATH = "/proc/net/arp"
    }

    override fun getMacForIp(ip: String): String? {
        val arpTable = readArpTable()
        return arpTable[ip]
    }

    override fun readArpTable(): Map<String, String> {
        val arpTable = mutableMapOf<String, String>()
        
        try {
            val reader = BufferedReader(FileReader(ARP_FILE_PATH))
            var line: String?
            
            // Skip header line
            reader.readLine()
            
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3]
                    if (mac != "00:00:00:00:00:00" && mac != "<incomplete>") {
                        arpTable[ip] = mac
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ARP table", e)
        }
        
        return arpTable
    }

    private fun parseArpLine(line: String): Pair<String, String>? {
        return try {
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 4) {
                val ip = parts[0]
                val mac = parts[3]
                if (mac != "00:00:00:00:00:00" && mac != "<incomplete>") {
                    Pair(ip, mac)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ARP line: $line", e)
            null
        }
    }
}
