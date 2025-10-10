package com.example.shieldshare.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.shieldshare.R
import com.example.shieldshare.databinding.FragmentDashboardBinding
import com.example.shieldshare.managers.vpn.VpnConfig
import com.example.shieldshare.managers.vpn.VpnManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var vpnManager: VpnManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnStartVpn.setOnClickListener {
            vpnManager.start(VpnConfig(captureAll = false))
        }
        binding.btnStopVpn.setOnClickListener { vpnManager.stop() }
        binding.btnGoSettings.setOnClickListener {
            // Navigate to SettingsFragment
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
