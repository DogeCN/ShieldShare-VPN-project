package com.example.shieldshare.ui.dashboard

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.shieldshare.R
import com.example.shieldshare.databinding.FragmentDashboardBinding
import com.example.shieldshare.managers.proxy.ProxyConfig
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.proxy.ProxyType
import com.example.shieldshare.managers.vpn.VpnManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding
        get() = _binding!!

    @Inject lateinit var vpnManager: VpnManager
    @Inject lateinit var proxyServer: ProxyServer

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TODO: JIALU - Add traffic statistics display, connected clients list, and real-time
        // monitoring UI

        // Hanchen's VPN buttons (restored to original functionality)
        binding.btnStartVpn.setOnClickListener {
            // TODO: Hanchen - Implement VPN start functionality
            // vpnManager.connectVpn(VpnConfig())
        }
        binding.btnStopVpn.setOnClickListener {
            // TODO: Hanchen - Implement VPN stop functionality
            // vpnManager.disconnectVpn()
        }

        // Carlos's Proxy Server Testing buttons
        binding.btnStartProxy.setOnClickListener { startProxyServer() }
        binding.btnStopProxy.setOnClickListener { stopProxyServer() }

        binding.btnGoSettings.setOnClickListener {
            // TODO: JIALU - Enhance settings screen with traffic statistics and user preferences
            // Navigate to SettingsFragment
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    private fun startProxyServer() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val config =
                        ProxyConfig(
                                port = 8080,
                                authEnabled = false,
                                allowedClients = emptyList(),
                                proxyType = ProxyType.BOTH
                        )

                val result = proxyServer.startProxy(config)
                result.fold(
                        onSuccess = { proxyInstance ->
                            Log.i(
                                    "DashboardFragment",
                                    "Proxy server started successfully: $proxyInstance"
                            )
                            binding.btnStartProxy.text = "Proxy Running (Port 8080)"
                            binding.btnStartProxy.isEnabled = false
                            binding.btnStopProxy.isEnabled = true
                        },
                        onFailure = { error ->
                            Log.e("DashboardFragment", "Failed to start proxy server", error)
                            binding.btnStartProxy.text = "Start Failed - Check Logs"
                        }
                )
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Exception starting proxy server", e)
                binding.btnStartProxy.text = "Start Failed - Check Logs"
            }
        }
    }

    private fun stopProxyServer() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = proxyServer.stopProxy()
                result.fold(
                        onSuccess = {
                            Log.i("DashboardFragment", "Proxy server stopped successfully")
                            binding.btnStartProxy.text = "Start Proxy Server"
                            binding.btnStartProxy.isEnabled = true
                            binding.btnStopProxy.isEnabled = false
                        },
                        onFailure = { error ->
                            Log.e("DashboardFragment", "Failed to stop proxy server", error)
                            binding.btnStopProxy.text = "Stop Failed - Check Logs"
                        }
                )
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Exception stopping proxy server", e)
                binding.btnStopProxy.text = "Stop Failed - Check Logs"
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
