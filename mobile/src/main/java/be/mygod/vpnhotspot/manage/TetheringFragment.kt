@file:Suppress("DEPRECATION")

package be.mygod.vpnhotspot.manage

import android.annotation.TargetApi
import android.bluetooth.BluetoothManager
import android.content.*
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import be.mygod.vpnhotspot.*
import be.mygod.vpnhotspot.databinding.FragmentTetheringBinding
import be.mygod.vpnhotspot.net.MacAddressCompat
import be.mygod.vpnhotspot.net.TetherType
import be.mygod.vpnhotspot.net.TetheringManager
import be.mygod.vpnhotspot.net.TetheringManager.localOnlyTetheredIfaces
import be.mygod.vpnhotspot.net.TetheringManager.tetheredIfaces
import be.mygod.vpnhotspot.net.monitor.TetherTimeoutMonitor
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat
import be.mygod.vpnhotspot.net.wifi.WifiApDialogFragment
import be.mygod.vpnhotspot.net.wifi.WifiApManager
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.WifiApCommands
import be.mygod.vpnhotspot.util.*
import be.mygod.vpnhotspot.widget.SmartSnackbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.lang.reflect.InvocationTargetException
import java.net.NetworkInterface
import java.net.SocketException

class TetheringFragment : Fragment(), ServiceConnection, Toolbar.OnMenuItemClickListener {
    inner class ManagerAdapter : ListAdapter<Manager, RecyclerView.ViewHolder>(Manager),
        TetheringManager.TetheringEventCallback {
        internal val repeaterManager by lazy { RepeaterManager(this@TetheringFragment) }
        @get:RequiresApi(26)
        internal val localOnlyHotspotManager by lazy @TargetApi(26) { LocalOnlyHotspotManager(this@TetheringFragment) }
        @get:RequiresApi(24)
        internal val bluetoothManager by lazy {
            if (Build.VERSION.SDK_INT >= 24) requireContext().getSystemService<BluetoothManager>()?.adapter?.let {
                TetherManager.Bluetooth(this@TetheringFragment, it)
            } else null
        }
        @get:RequiresApi(24)
        private val tetherManagers by lazy @TargetApi(24) {
            listOfNotNull(
                TetherManager.Wifi(this@TetheringFragment),
                TetherManager.Usb(this@TetheringFragment),
                bluetoothManager,
            )
        }
        @get:RequiresApi(30)
        private val tetherManagers30 by lazy @TargetApi(30) {
            listOf(TetherManager.Ethernet(this@TetheringFragment), TetherManager.Ncm(this@TetheringFragment))
        }
        private val wifiManagerLegacy by lazy { TetherManager.WifiLegacy(this@TetheringFragment) }

        var activeIfaces = emptyList<String>()
        var localOnlyIfaces = emptyList<String>()
        var erroredIfaces = emptyList<String>()
        private var listDeferred = CompletableDeferred<List<Manager>>(emptyList())
        fun updateEnabledTypes() {
            this@TetheringFragment.enabledTypes =
                (activeIfaces + localOnlyIfaces).map { TetherType.ofInterface(it) }.toSet()
        }

        val lastErrors = mutableMapOf<String, Int>()
        override fun onError(ifName: String, error: Int) {
            if (error == 0) lastErrors.remove(ifName) else lastErrors[ifName] = error
        }

        suspend fun notifyTetherTypeChanged() {
            updateEnabledTypes()
            val lastList = listDeferred.await()
            var first = lastList.indexOfFirst { it is InterfaceManager }
            if (first >= 0) notifyItemRangeChanged(first, lastList.indexOfLast { it is InterfaceManager } - first + 1)
            first = lastList.indexOfLast { it !is TetherManager } + 1
            notifyItemRangeChanged(first, lastList.size - first)
        }

        fun update() {
            val deferred = CompletableDeferred<List<Manager>>()
            listDeferred = deferred
            ifaceLookup = try {
                NetworkInterface.getNetworkInterfaces().asSequence().associateBy { it.name }
            } catch (e: SocketException) {
                Timber.d(e)
                emptyMap()
            }

            val list = ArrayList<Manager>()
            if (Services.p2p != null) list.add(repeaterManager)
//            if (Build.VERSION.SDK_INT >= 26) list.add(localOnlyHotspotManager)
//            val monitoredIfaces = binder?.monitoredIfaces ?: emptyList()
//            updateMonitorList(activeIfaces - monitoredIfaces)
//            list.addAll((activeIfaces + monitoredIfaces).toSortedSet()
//                    .map { InterfaceManager(this@TetheringFragment, it) })
//            list.add(ManageBar)
//            if (Build.VERSION.SDK_INT >= 24) {
//                list.addAll(tetherManagers)
//                tetherManagers.forEach { it.updateErrorMessage(erroredIfaces, lastErrors) }
//            }
//            if (Build.VERSION.SDK_INT >= 30) {
//                list.addAll(tetherManagers30)
//                tetherManagers30.forEach { it.updateErrorMessage(erroredIfaces, lastErrors) }
//            }
//            if (Build.VERSION.SDK_INT < 26) {
//                list.add(wifiManagerLegacy)
//                wifiManagerLegacy.onTetheringStarted()
//            }
            submitList(list) { deferred.complete(list) }
        }

        override fun getItemViewType(position: Int) = getItem(position).type
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Manager.createViewHolder(LayoutInflater.from(parent.context), parent, viewType)
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = getItem(position).bindTo(holder)
    }

    @RequiresApi(29)
    val startRepeater = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requireActivity().startForegroundService(Intent(activity, RepeaterService::class.java)) else {
            Snackbar.make((activity as MainActivity).binding.fragmentHolder,
                R.string.repeater_missing_location_permissions, Snackbar.LENGTH_LONG).show()
        }
    }
    @RequiresApi(26)
    val startLocalOnlyHotspot = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        adapter.localOnlyHotspotManager.start(requireContext())
    }
    @RequiresApi(31)
    val requestBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) adapter.bluetoothManager!!.ensureInit(requireContext())
    }

    var ifaceLookup: Map<String, NetworkInterface> = emptyMap()
    var enabledTypes = emptySet<TetherType>()
    private lateinit var binding: FragmentTetheringBinding
    var binder: TetheringService.Binder? = null
    private val adapter = ManagerAdapter()
    private val receiver = broadcastReceiver { _, intent ->
        adapter.activeIfaces = intent.tetheredIfaces ?: return@broadcastReceiver
        adapter.localOnlyIfaces = intent.localOnlyTetheredIfaces ?: return@broadcastReceiver
        adapter.erroredIfaces = intent.getStringArrayListExtra(TetheringManager.EXTRA_ERRORED_TETHER)
            ?: return@broadcastReceiver
        adapter.updateEnabledTypes()
        adapter.update()
    }

    private fun updateMonitorList(canMonitor: List<String> = emptyList()) {
        val activity = activity as? MainActivity
        val item = activity?.binding?.toolbar?.menu?.findItem(R.id.monitor) ?: return   // assuming no longer foreground
        item.isNotGone = canMonitor.isNotEmpty()
        item.subMenu.apply {
            clear()
            for (iface in canMonitor.sorted()) add(iface).setOnMenuItemClickListener {
                ContextCompat.startForegroundService(activity, Intent(activity, TetheringService::class.java)
                        .putExtra(TetheringService.EXTRA_ADD_INTERFACE_MONITOR, iface))
                true
            }
        }
    }

    private var apConfigurationRunning = false

//    override fun onMenuItemClick(item: MenuItem?): Boolean {
//        return when (item?.itemId) {
//            R.id.configuration -> item.subMenu.run {
//                findItem(R.id.configuration_repeater).isNotGone = Services.p2p != null
//                findItem(R.id.configuration_temp_hotspot).isNotGone =
//                        adapter.localOnlyHotspotManager.binder?.configuration != null
//                true
//            }
//            R.id.configuration_repeater -> {
//                adapter.repeaterManager.configure()
//                true
//            }
//            R.id.configuration_temp_hotspot -> {
//                WifiApDialogFragment().apply {
//                    arg(WifiApDialogFragment.Arg(adapter.localOnlyHotspotManager.binder?.configuration ?: return false,
//                            readOnly = true))
//                    // no need for callback
//                }.showAllowingStateLoss(parentFragmentManager)
//                true
//            }
//            R.id.configuration_ap -> if (apConfigurationRunning) false else {
//                apConfigurationRunning = true
//                viewLifecycleOwner.lifecycleScope.launchWhenCreated {
//                    try {
//                        WifiApManager.configuration
//                    } catch (e: InvocationTargetException) {
//                        if (e.targetException !is SecurityException) Timber.w(e)
//                        try {
//                            RootManager.use { it.execute(WifiApCommands.GetConfiguration()) }
//                        } catch (_: CancellationException) {
//                            null
//                        } catch (eRoot: Exception) {
//                            eRoot.addSuppressed(e)
//                            if (Build.VERSION.SDK_INT !in 26..29 || eRoot.getRootCause() !is SecurityException) {
//                                Timber.w(eRoot)
//                            }
//                            SmartSnackbar.make(eRoot).show()
//                            null
//                        }
//                    } catch (e: IllegalArgumentException) {
//                        Timber.w(e)
//                        SmartSnackbar.make(e).show()
//                        null
//                    }?.let { configuration ->
//                        WifiApDialogFragment().apply {
//                            arg(WifiApDialogFragment.Arg(configuration))
//                            key()
//                        }.showAllowingStateLoss(parentFragmentManager)
//                    }
//                    apConfigurationRunning = false
//                }
//                true
//            }
//            else -> false
//        }
//    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        AlertDialogFragment.setResultListener<WifiApDialogFragment, WifiApDialogFragment.Arg>(this) { which, ret ->
            if (which == DialogInterface.BUTTON_POSITIVE) viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val configuration = ret!!.configuration
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT in 28 until 30 &&
                        configuration.isAutoShutdownEnabled != TetherTimeoutMonitor.enabled) try {
                    TetherTimeoutMonitor.setEnabled(configuration.isAutoShutdownEnabled)
                } catch (e: Exception) {
                    Timber.w(e)
                    SmartSnackbar.make(e).show()
                }
                val success = try {
                    WifiApManager.setConfiguration(configuration)
                } catch (e: InvocationTargetException) {
                    try {
                        RootManager.use { it.execute(WifiApCommands.SetConfiguration(configuration)) }
                    } catch (_: CancellationException) {
                    } catch (eRoot: Exception) {
                        eRoot.addSuppressed(e)
                        Timber.w(eRoot)
                        SmartSnackbar.make(eRoot).show()
                        null
                    }
                }
                if (success == false) SmartSnackbar.make(R.string.configuration_rejected).show()
            }
        }
        binding = FragmentTetheringBinding.inflate(inflater, container, false)
        binding.interfaces.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        binding.interfaces.itemAnimator = DefaultItemAnimator()
        binding.interfaces.adapter = adapter
        adapter.update()
        ServiceForegroundConnector(this, this, TetheringService::class)

        // TODO: Hard-coded networkName and password


        val networkName = RepeaterService.networkName
        val passphrase = RepeaterService.passphrase

        base = SoftApConfigurationCompat(
            ssid = networkName,
            passphrase = passphrase,
            securityType = SoftApConfiguration.SECURITY_TYPE_WPA2_PSK,  // is not actually used
            isAutoShutdownEnabled = RepeaterService.isAutoShutdownEnabled,
            shutdownTimeoutMillis = RepeaterService.shutdownTimeoutMillis).apply {
            bssid = RepeaterService.deviceAddress
            setChannel(RepeaterService.operatingChannel, RepeaterService.operatingBand)
            setMacRandomizationEnabled(WifiApManager.p2pMacRandomizationSupported)
        }

        (activity as MainActivity).binding.toolbar.apply {
            inflateMenu(R.menu.toolbar_configuration)
            setOnMenuItemClickListener(this@TetheringFragment)
        }
        return binding.root
    }

    private lateinit var base: SoftApConfigurationCompat

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.share_qr -> {
                val arguments = generateConfig(false).toQrCode()
                Timber.d("arguments: $arguments")
                QRCodeDialog().withArg(arguments).showAllowingStateLoss(childFragmentManager)
                true
            }
            else -> false
        }
    }

    private fun generateConfig(full: Boolean = true) = base.copy(
        ssid = base.ssid,
        passphrase = if (base.passphrase.isNullOrEmpty()) null else base.passphrase.toString()
    ).apply {
        Timber.d("ssid: $ssid")
        Timber.d("passphrase: $passphrase")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as MainActivity).binding.toolbar.apply {
            menu.clear()
            setOnMenuItemClickListener(null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 27) ManageBar.Data.notifyChange()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = service as TetheringService.Binder
        service.routingsChanged[this] = { lifecycleScope.launchWhenStarted { adapter.update() } }
        requireContext().registerReceiver(receiver, IntentFilter(TetheringManager.ACTION_TETHER_STATE_CHANGED))
        if (Build.VERSION.SDK_INT >= 30) {
            TetheringManager.registerTetheringEventCallback(null, adapter)
            TetherType.listener[this] = { lifecycleScope.launchWhenStarted { adapter.notifyTetherTypeChanged() } }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        (binder ?: return).routingsChanged -= this
        binder = null
        if (Build.VERSION.SDK_INT >= 30) {
            TetherType.listener -= this
            TetheringManager.unregisterTetheringEventCallback(adapter)
            adapter.lastErrors.clear()
        }
        requireContext().unregisterReceiver(receiver)
    }
}
