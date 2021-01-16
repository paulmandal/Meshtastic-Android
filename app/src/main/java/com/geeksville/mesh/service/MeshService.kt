package com.geeksville.mesh.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.RemoteException
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.content.edit
import com.geeksville.analytics.DataPair
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.android.ServiceClient
import com.geeksville.android.isGooglePlayAvailable
import com.geeksville.concurrent.handledLaunch
import com.geeksville.mesh.*
import com.geeksville.mesh.MeshProtos.MeshPacket
import com.geeksville.mesh.MeshProtos.ToRadio
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.service.SoftwareUpdateService.Companion.ProgressNotStarted
import com.geeksville.util.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.math.absoluteValue

/**
 * Handles all the communication with android apps.  Also keeps an internal model
 * of the network state.
 *
 * Note: this service will go away once all clients are unbound from it.
 * Warning: do not override toString, it causes infinite recursion on some androids (because contextWrapper.getResources calls to string
 */
class MeshService : Service(), Logging {

    companion object : Logging {

        /// special broadcast address
        const val NODENUM_BROADCAST = (0xffffffff).toInt()

        /// Intents broadcast by MeshService
        const val ACTION_RECEIVED_DATA = "$prefix.RECEIVED_DATA"
        const val ACTION_NODE_CHANGE = "$prefix.NODE_CHANGE"
        const val ACTION_MESH_CONNECTED = "$prefix.MESH_CONNECTED"
        const val ACTION_MESSAGE_STATUS = "$prefix.MESSAGE_STATUS"

        class IdNotFoundException(id: String) : Exception("ID not found $id")
        class NodeNumNotFoundException(id: Int) : Exception("NodeNum not found $id")
        class IsUpdatingException() : Exception("Operation prohibited during firmware update")

        /**
         * Talk to our running service and try to set a new device address.  And then immediately
         * call start on the service to possibly promote our service to be a foreground service.
         */
        fun changeDeviceAddress(context: Context, service: IMeshService, address: String?) {
            service.setDeviceAddress(address)
            startService(context)
        }

        fun createIntent() = Intent().setClassName(
            "com.geeksville.mesh",
            "com.geeksville.mesh.service.MeshService"
        )
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTED,
        DEVICE_SLEEP // device is in LS sleep state, it will reconnected to us over bluetooth once it has data
    }

    /// A mapping of receiver class name to package name - used for explicit broadcasts
    private val clientPackages = mutableMapOf<String, String>()
    private val serviceNotifications = MeshServiceNotifications(this)
    private val serviceBroadcasts = MeshServiceBroadcasts(this, clientPackages) { connectionState }
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var connectionState = ConnectionState.DISCONNECTED
    private var packetRepo: PacketRepository? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null

    // If we've ever read a valid region code from our device it will be here
    var curRegionValue = MeshProtos.RegionCode.Unset_VALUE

    val radio = ServiceClient {
        IRadioInterfaceService.Stub.asInterface(it).apply {
            // Now that we are connected to the radio service, tell it to connect to the radio
            connect()
        }
    }

    private val locationCallback = MeshServiceLocationCallback(
        ::sendPositionScoped,
        onSendPositionFailed = { onConnectionChanged(ConnectionState.DEVICE_SLEEP) },
        getNodeNum = { myNodeNum }
    )

    private fun getSenderName(): String {
        val recentFrom = recentReceivedTextPacket?.from // safe, immutable copy
        return if (recentFrom != null) {
            nodeDBbyID[recentFrom]?.user?.longName
                ?: recentFrom
        } else {
            getString(R.string.unknown_username)
        }
    }

    /// A text message that has a arrived since the last notification update
    private var recentReceivedTextPacket: DataPacket? = null

    private val notificationSummary
        get() = when (connectionState) {
            ConnectionState.CONNECTED -> getString(R.string.connected_count).format(
                numOnlineNodes,
                numNodes
            )
            ConnectionState.DISCONNECTED -> getString(R.string.disconnected)
            ConnectionState.DEVICE_SLEEP -> getString(R.string.device_sleeping)
        }

    private fun warnUserAboutLocation() {
        Toast.makeText(
            this,
            getString(R.string.location_disabled),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * start our location requests (if they weren't already running)
     *
     * per https://developer.android.com/training/location/change-location-settings
     */
    @SuppressLint("MissingPermission")
    @UiThread
    private fun startLocationRequests() {
        // FIXME - currently we don't support location reading without google play
        if (fusedLocationClient == null && isGooglePlayAvailable(this)) {
            GeeksvilleApplication.analytics.track("location_start") // Figure out how many users needed to use the phone GPS

            val request = LocationRequest.create().apply {
                interval =
                    5 * 60 * 1000 // FIXME, do more like once every 5 mins while we are connected to our radio _and_ someone else is in the mesh

                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            val builder = LocationSettingsRequest.Builder().addLocationRequest(request)
            val locationClient = LocationServices.getSettingsClient(this)
            val locationSettingsResponse = locationClient.checkLocationSettings(builder.build())

            locationSettingsResponse.addOnSuccessListener {
                debug("We are now successfully listening to the GPS")
            }

            locationSettingsResponse.addOnFailureListener { exception ->
                errormsg("Failed to listen to GPS")

                when (exception) {
                    is ResolvableApiException ->
                        exceptionReporter {
                            // Location settings are not satisfied, but this can be fixed
                            // by showing the user a dialog.

                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().
                            // exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)

                            // For now just punt and show a dialog
                            warnUserAboutLocation()
                        }
                    is ApiException ->
                        when (exception.statusCode) {
                            17 ->
                                // error: cancelled by user
                                errormsg("User cancelled location access", exception)
                            8502 ->
                                // error: settings change unavailable
                                errormsg(
                                    "Settings-change-unavailable, user disabled location access (globally?)",
                                    exception
                                )
                            else ->
                                Exceptions.report(exception)
                        }
                    else ->
                        Exceptions.report(exception)
                }
            }

            val client = LocationServices.getFusedLocationProviderClient(this)

            // FIXME - should we use Looper.myLooper() in the third param per https://github.com/android/location-samples/blob/432d3b72b8c058f220416958b444274ddd186abd/LocationUpdatesForegroundService/app/src/main/java/com/google/android/gms/location/sample/locationupdatesforegroundservice/LocationUpdatesService.java
            client.requestLocationUpdates(request, locationCallback, null)

            fusedLocationClient = client
        }
    }

    private fun stopLocationRequests() {
        if (fusedLocationClient != null) {
            debug("Stopping location requests")
            GeeksvilleApplication.analytics.track("location_stop")
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            fusedLocationClient = null
        }
    }

    /// Safely access the radio service, if not connected an exception will be thrown
    private val connectedRadio: IRadioInterfaceService
        get() = (if (connectionState == ConnectionState.CONNECTED) radio.serviceP else null)
            ?: throw RadioNotConnectedException()

    /// Send a command/packet to our radio.  But cope with the possiblity that we might start up
    /// before we are fully bound to the RadioInterfaceService
    private fun sendToRadio(p: ToRadio.Builder) {
        val b = p.build().toByteArray()

        if(SoftwareUpdateService.isUpdating)
            throw IsUpdatingException()

        connectedRadio.sendToRadio(b)
    }

    /**
     * Send a mesh packet to the radio, if the radio is not currently connected this function will throw NotConnectedException
     */
    private fun sendToRadio(packet: MeshPacket) {
        sendToRadio(ToRadio.newBuilder().apply {
            this.packet = packet
        })
    }

    private fun updateNotification() = serviceNotifications.updateNotification(
        recentReceivedTextPacket,
        notificationSummary,
        getSenderName()
    )

    /**
     * tell android not to kill us
     */
    private fun startForeground() {
        val a = RadioInterfaceService.getBondedDeviceAddress(this)
        val wantForeground = a != null && a != "n"

        info("Requesting foreground service=$wantForeground")

        // We always start foreground because that's how our service is always started (if we didn't then android would kill us)
        // but if we don't really need foreground we immediately stop it.
        val notification = serviceNotifications.createNotification(
            recentReceivedTextPacket,
            notificationSummary,
            getSenderName()
        )
        startForeground(serviceNotifications.notifyId, notification)
        if (!wantForeground) {
            stopForeground(true)
        }
    }

    override fun onCreate() {
        super.onCreate()

        info("Creating mesh service")

        val packetsDao = MeshtasticDatabase.getDatabase(applicationContext).packetDao()
        packetRepo = PacketRepository(packetsDao)

        // Switch to the IO thread
        serviceScope.handledLaunch {
            loadSettings() // Load our last known node DB

            // we listen for messages from the radio receiver _before_ trying to create the service
            val filter = IntentFilter().apply {
                addAction(RadioInterfaceService.RECEIVE_FROMRADIO_ACTION)
                addAction(RadioInterfaceService.RADIO_CONNECTED_ACTION)
            }
            registerReceiver(radioInterfaceReceiver, filter)

            // We in turn need to use the radiointerface service
            val intent = Intent(this@MeshService, RadioInterfaceService::class.java)
            // intent.action = IMeshService::class.java.name
            radio.connect(this@MeshService, intent, Context.BIND_AUTO_CREATE)

            // the rest of our init will happen once we are in radioConnection.onServiceConnected
        }
    }

    /**
     * If someone binds to us, this will be called after on create
     */
    override fun onBind(intent: Intent?): IBinder? {
        startForeground()

        return binder
    }

    /**
     * If someone starts us (or restarts us) this will be called after onCreate)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        info("Destroying mesh service")

        // This might fail if we get destroyed before the handledLaunch completes
        ignoreException {
            unregisterReceiver(radioInterfaceReceiver)
        }

        radio.close()
        saveSettings()

        super.onDestroy()
        serviceJob.cancel()
    }

    ///
    /// BEGINNING OF MODEL - FIXME, move elsewhere
    ///

    private fun getPrefs() = getSharedPreferences("service-prefs", Context.MODE_PRIVATE)

    /// Save information about our mesh to disk, so we will have it when we next start the service (even before we hear from our device)
    private fun saveSettings() {
        myNodeInfo?.let { myInfo ->
            val settings = MeshServiceSettingsData(
                myInfo = myInfo,
                nodeDB = nodeDBbyNodeNum.values.toTypedArray(),
                messages = recentDataPackets.toTypedArray(),
                regionCode = curRegionValue
            )
            val json = Json { isLenient = true }
            val asString = json.encodeToString(MeshServiceSettingsData.serializer(), settings)
            debug("Saving settings")
            getPrefs().edit(commit = true) {
                // FIXME, not really ideal to store this bigish blob in preferences
                putString("json", asString)
            }
        }
    }

    private fun installNewNodeDB(newMyNodeInfo: MyNodeInfo, nodes: Array<NodeInfo>) {
        discardNodeDB() // Get rid of any old state

        myNodeInfo = newMyNodeInfo

        // put our node array into our two different map representations
        nodeDBbyNodeNum.putAll(nodes.map { Pair(it.num, it) })
        nodeDBbyID.putAll(nodes.mapNotNull {
            it.user?.let { user -> // ignore records that don't have a valid user
                Pair(
                    user.id,
                    it
                )
            }
        })
    }

    private fun loadSettings() {
        try {
            getPrefs().getString("json", null)?.let { asString ->

                val json = Json { isLenient = true }
                val settings = json.decodeFromString(MeshServiceSettingsData.serializer(), asString)
                installNewNodeDB(settings.myInfo, settings.nodeDB)
                curRegionValue = settings.regionCode

                // Note: we do not haveNodeDB = true because that means we've got a valid db from a real device (rather than this possibly stale hint)

                recentDataPackets.addAll(settings.messages)
            }
        } catch (ex: Exception) {
            errormsg("Ignoring error loading saved state for service: ${ex.message}")
        }
    }

    /**
     * discard entire node db & message state - used when downloading a new db from the device
     */
    private fun discardNodeDB() {
        debug("Discarding NodeDB")
        myNodeInfo = null
        nodeDBbyNodeNum.clear()
        nodeDBbyID.clear()
        // recentDataPackets.clear() We do NOT want to clear this, because it is the record of old messages the GUI still might want to show
        haveNodeDB = false
    }

    var myNodeInfo: MyNodeInfo? = null

    private var radioConfig: MeshProtos.RadioConfig? = null

    /// True after we've done our initial node db init
    @Volatile
    private var haveNodeDB = false

    // The database of active nodes, index is the node number
    private val nodeDBbyNodeNum = mutableMapOf<Int, NodeInfo>()

    /// The database of active nodes, index is the node user ID string
    /// NOTE: some NodeInfos might be in only nodeDBbyNodeNum (because we don't yet know
    /// an ID).  But if a NodeInfo is in both maps, it must be one instance shared by
    /// both datastructures.
    private val nodeDBbyID = mutableMapOf<String, NodeInfo>()

    ///
    /// END OF MODEL
    ///

    val deviceVersion get() = DeviceVersion(myNodeInfo?.firmwareVersion ?: "")

    /// Map a nodenum to a node, or throw an exception if not found
    private fun toNodeInfo(n: Int) = nodeDBbyNodeNum[n] ?: throw NodeNumNotFoundException(
        n
    )

    /// Map a nodenum to the nodeid string, or return null if not present or no id found
    private fun toNodeID(n: Int) =
        if (n == NODENUM_BROADCAST) DataPacket.ID_BROADCAST else nodeDBbyNodeNum[n]?.user?.id

    /// given a nodenum, return a db entry - creating if necessary
    private fun getOrCreateNodeInfo(n: Int) =
        nodeDBbyNodeNum.getOrPut(n) { -> NodeInfo(n) }

    /// Map a userid to a node/ node num, or throw an exception if not found
    private fun toNodeInfo(id: String) =
        nodeDBbyID[id]
            ?: throw IdNotFoundException(
                id
            )

    private val numNodes get() = nodeDBbyNodeNum.size

    /**
     * How many nodes are currently online (including our local node)
     */
    private val numOnlineNodes get() = nodeDBbyNodeNum.values.count { it.isOnline }

    private fun toNodeNum(id: String) =
        when (id) {
            DataPacket.ID_BROADCAST -> NODENUM_BROADCAST
            DataPacket.ID_LOCAL -> myNodeNum
            else -> toNodeInfo(id).num
        }

    /// A helper function that makes it easy to update node info objects
    private fun updateNodeInfo(nodeNum: Int, updatefn: (NodeInfo) -> Unit) {
        val info = getOrCreateNodeInfo(nodeNum)
        updatefn(info)

        // This might have been the first time we know an ID for this node, so also update the by ID map
        val userId = info.user?.id.orEmpty()
        if (userId.isNotEmpty())
            nodeDBbyID[userId] = info

        // parcelable is busted
        serviceBroadcasts.broadcastNodeChange(info)
    }

    /// My node num
    private val myNodeNum
        get() = myNodeInfo?.myNodeNum
            ?: throw RadioNotConnectedException("We don't yet have our myNodeInfo")

    /// My node ID string
    private val myNodeID get() = toNodeID(myNodeNum)

    /// Generate a new mesh packet builder with our node as the sender, and the specified node num
    private fun newMeshPacketTo(idNum: Int) = MeshPacket.newBuilder().apply {
        val useShortAddresses = (myNodeInfo?.nodeNumBits ?: 8) != 32

        if (myNodeInfo == null)
            throw RadioNotConnectedException()

        from = myNodeNum

        // We might need to change broadcast addresses to work with old device loads
        to = if (useShortAddresses && idNum == NODENUM_BROADCAST) 255 else idNum
    }

    /**
     * Generate a new mesh packet builder with our node as the sender, and the specified recipient
     *
     * If id is null we assume a broadcast message
     */
    private fun newMeshPacketTo(id: String) =
        newMeshPacketTo(toNodeNum(id))

    /**
     * Helper to make it easy to build a subpacket in the proper protobufs
     *
     * If destId is null we assume a broadcast message
     */
    private fun buildMeshPacket(
        destId: String,
        wantAck: Boolean = false,
        id: Int = 0,
        initFn: MeshProtos.SubPacket.Builder.() -> Unit
    ): MeshPacket = newMeshPacketTo(destId).apply {
        this.wantAck = wantAck
        this.id = id
        decoded = MeshProtos.SubPacket.newBuilder().also {
            initFn(it)
        }.build()
    }.build()

    // FIXME - possible kotlin bug in 1.3.72 - it seems that if we start with the (globally shared) emptyList,
    // then adding items are affecting that shared list rather than a copy.   This was causing aliasing of
    // recentDataPackets with messages.value in the GUI.  So if the current list is empty we are careful to make a new list
    private var recentDataPackets = mutableListOf<DataPacket>()

    /// Generate a DataPacket from a MeshPacket, or null if we didn't have enough data to do so
    private fun toDataPacket(packet: MeshPacket): DataPacket? {
        return if (!packet.hasDecoded() || !packet.decoded.hasData()) {
            // We never convert packets that are not DataPackets
            null
        } else {
            val data = packet.decoded.data
            val bytes = data.payload.toByteArray()
            val fromId = toNodeID(packet.from)
            val toId = toNodeID(packet.to)

            // If the rxTime was not set by the device (because device software was old), guess at a time
            val rxTime = if (packet.rxTime == 0) packet.rxTime else currentSecond()

            when {
                fromId == null -> {
                    errormsg("Ignoring data from ${packet.from} because we don't yet know its ID")
                    null
                }
                toId == null -> {
                    errormsg("Ignoring data to ${packet.to} because we don't yet know its ID")
                    null
                }
                else -> {
                    DataPacket(
                        from = fromId,
                        to = toId,
                        time = rxTime * 1000L,
                        id = packet.id,
                        dataType = data.portnumValue,
                        bytes = bytes
                    )
                }
            }
        }
    }

    /// Syntactic sugar to create data subpackets
    private fun makeData(portNum: Int, bytes: ByteString) = MeshProtos.Data.newBuilder().also {
        it.portnumValue = portNum
        it.payload = bytes
    }.build()

    private fun toMeshPacket(p: DataPacket): MeshPacket {
        return buildMeshPacket(p.to!!, id = p.id, wantAck = true) {
            data = makeData(p.dataType, ByteString.copyFrom(p.bytes))
        }
    }

    private fun rememberDataPacket(dataPacket: DataPacket) {
        // discard old messages if needed then add the new one
        while (recentDataPackets.size > 50)
            recentDataPackets.removeAt(0)

        // FIXME - possible kotlin bug in 1.3.72 - it seems that if we start with the (globally shared) emptyList,
        // then adding items are affecting that shared list rather than a copy.   This was causing aliasing of
        // recentDataPackets with messages.value in the GUI.  So if the current list is empty we are careful to make a new list
        if (recentDataPackets.isEmpty())
            recentDataPackets = mutableListOf(dataPacket)
        else
            recentDataPackets.add(dataPacket)
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedData(packet: MeshPacket) {
        myNodeInfo?.let { myInfo ->
            val data = packet.decoded.data
            val bytes = data.payload.toByteArray()
            val fromId = toNodeID(packet.from)
            val dataPacket = toDataPacket(packet)

            if (dataPacket != null) {

                if (myInfo.myNodeNum == packet.from)
                    debug("Ignoring packet sent from our node")
                else {
                    debug("Received data from $fromId, portnum=${data.portnumValue} ${bytes.size} bytes")

                    dataPacket.status = MessageStatus.RECEIVED
                    rememberDataPacket(dataPacket)

                    when (data.portnumValue) {
                        Portnums.PortNum.TEXT_MESSAGE_APP_VALUE -> {
                            debug("Received CLEAR_TEXT from $fromId")

                            recentReceivedTextPacket = dataPacket
                            updateNotification()
                        }

                        // Handle new style position info
                        Portnums.PortNum.POSITION_APP_VALUE -> {
                            val rxTime = if (packet.rxTime != 0) packet.rxTime else currentSecond()
                            val u = MeshProtos.Position.parseFrom(data.payload)
                            handleReceivedPosition(packet.from, u, rxTime)
                        }

                        // Handle new style user info
                        Portnums.PortNum.NODEINFO_APP_VALUE -> {
                            val u = MeshProtos.User.parseFrom(data.payload)
                            handleReceivedUser(packet.from, u)
                        }
                    }

                    // We always tell other apps when new data packets arrive
                    serviceBroadcasts.broadcastReceivedData(dataPacket)

                    GeeksvilleApplication.analytics.track(
                        "num_data_receive",
                        DataPair(1)
                    )

                    GeeksvilleApplication.analytics.track(
                        "data_receive",
                        DataPair("num_bytes", bytes.size),
                        DataPair("type", data.portnumValue)
                    )
                }
            }
        }
    }

    /// Update our DB of users based on someone sending out a User subpacket
    private fun handleReceivedUser(fromNum: Int, p: MeshProtos.User) {
        updateNodeInfo(fromNum) {
            val oldId = it.user?.id.orEmpty()
            it.user = MeshUser(
                if (p.id.isNotEmpty()) p.id else oldId, // If the new update doesn't contain an ID keep our old value
                p.longName,
                p.shortName
            )
        }
    }

    /// Update our DB of users based on someone sending out a Position subpacket
    private fun handleReceivedPosition(
        fromNum: Int,
        p: MeshProtos.Position,
        defaultTime: Int = Position.currentTime()
    ) {
        updateNodeInfo(fromNum) {
            it.position = Position(p)
            updateNodeInfoTime(it, defaultTime)
        }
    }

    /// If packets arrive before we have our node DB, we delay parsing them until the DB is ready
    private val earlyReceivedPackets = mutableListOf<MeshPacket>()

    /// If apps try to send packets when our radio is sleeping, we queue them here instead
    private val offlineSentPackets = mutableListOf<DataPacket>()

    /** Keep a record of recently sent packets, so we can properly handle ack/nak */
    private val sentPackets = mutableMapOf<Int, DataPacket>()

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedMeshPacket(packet: MeshPacket) {
        if (haveNodeDB) {
            processReceivedMeshPacket(packet)
            onNodeDBChanged()
        } else {
            earlyReceivedPackets.add(packet)
            logAssert(earlyReceivedPackets.size < 128) // The max should normally be about 32, but if the device is messed up it might try to send forever
        }
    }

    private fun sendNow(p: DataPacket) {
        val packet = toMeshPacket(p)
        p.status = MessageStatus.ENROUTE
        p.time = System.currentTimeMillis() // update time to the actual time we started sending
        // debug("SENDING TO RADIO: $packet")
        sendToRadio(packet)
    }

    /// Process any packets that showed up too early
    private fun processEarlyPackets() {
        earlyReceivedPackets.forEach { processReceivedMeshPacket(it) }
        earlyReceivedPackets.clear()

        offlineSentPackets.forEach { p ->
            // encapsulate our payload in the proper protobufs and fire it off
            sendNow(p)
            serviceBroadcasts.broadcastMessageStatus(p)
        }
        offlineSentPackets.clear()
    }

    /**
     * Change the status on a data packet and update watchers
     */
    private fun changeStatus(p: DataPacket, m: MessageStatus) {
        p.status = m
        serviceBroadcasts.broadcastMessageStatus(p)
    }

    /**
     * Handle an ack/nak packet by updating sent message status
     */
    private fun handleAckNak(isAck: Boolean, id: Int) {
        sentPackets.remove(id)?.let { p ->
            changeStatus(p, if (isAck) MessageStatus.DELIVERED else MessageStatus.ERROR)
        }
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun processReceivedMeshPacket(packet: MeshPacket) {
        val fromNum = packet.from

        // FIXME, perhaps we could learn our node ID by looking at any to packets the radio
        // decided to pass through to us (except for broadcast packets)
        //val toNum = packet.to

        // debug("Recieved: $packet")
        val p = packet.decoded

        val packetToSave = Packet(
            UUID.randomUUID().toString(),
            "packet",
            System.currentTimeMillis(),
            packet.toString()
        )
        insertPacket(packetToSave)
        // If the rxTime was not set by the device (because device software was old), guess at a time
        val rxTime = if (packet.rxTime != 0) packet.rxTime else currentSecond()

        // Update last seen for the node that sent the packet, but also for _our node_ because anytime a packet passes
        // through our node on the way to the phone that means that local node is also alive in the mesh

        updateNodeInfo(myNodeNum) {
            it.position = it.position?.copy(time = currentSecond())
        }

        if (p.hasPosition())
            handleReceivedPosition(fromNum, p.position, rxTime)
        else
            updateNodeInfo(fromNum) {
                // Update our last seen based on any valid timestamps.  If the device didn't provide a timestamp make one
                updateNodeInfoTime(it, rxTime)
            }

        if (p.hasData())
            handleReceivedData(packet)

        if (p.hasUser())
            handleReceivedUser(fromNum, p.user)

        if (p.successId != 0)
            handleAckNak(true, p.successId)

        if (p.failId != 0)
            handleAckNak(false, p.failId)
    }

    private fun insertPacket(packetToSave: Packet) {
        serviceScope.handledLaunch {
            info("insert: ${packetToSave.message_type} = ${packetToSave.raw_message.toOneLineString()}")
            packetRepo!!.insert(packetToSave)
        }
    }

    private fun currentSecond() = (System.currentTimeMillis() / 1000).toInt()


    /// If we just changed our nodedb, we might want to do somethings
    private fun onNodeDBChanged() {
        updateNotification()

        // we don't ask for GPS locations from android if our device has a built in GPS
        // Note: myNodeInfo can go away if we lose connections, so it might be null
        if (myNodeInfo?.hasGPS != true) {
            // If we have at least one other person in the mesh, send our GPS position otherwise stop listening to GPS

            serviceScope.handledLaunch(Dispatchers.Main) {
                if (numOnlineNodes >= 2)
                    startLocationRequests()
                else
                    stopLocationRequests()
            }
        } else
            debug("Our radio has a built in GPS, so not reading GPS in phone")
    }


    /**
     * Send in analytics about mesh connection
     */
    private fun reportConnection() {
        val radioModel = DataPair("radio_model", myNodeInfo?.model ?: "unknown")
        GeeksvilleApplication.analytics.track(
            "mesh_connect",
            DataPair("num_nodes", numNodes),
            DataPair("num_online", numOnlineNodes),
            radioModel
        )

        // Once someone connects to hardware start tracking the approximate number of nodes in their mesh
        // this allows us to collect stats on what typical mesh size is and to tell difference between users who just
        // downloaded the app, vs has connected it to some hardware.
        GeeksvilleApplication.analytics.setUserInfo(
            DataPair("num_nodes", numNodes),
            radioModel
        )
    }

    private var sleepTimeout: Job? = null

    /// msecs since 1970 we started this connection
    private var connectTimeMsec = 0L

    /// Called when we gain/lose connection to our radio
    private fun onConnectionChanged(c: ConnectionState) {
        debug("onConnectionChanged=$c")

        /// Perform all the steps needed once we start waiting for device sleep to complete
        fun startDeviceSleep() {
            // Just in case the user uncleanly reboots the phone, save now (we normally save in onDestroy)
            saveSettings()

            // lost radio connection, therefore no need to keep listening to GPS
            stopLocationRequests()

            if (connectTimeMsec != 0L) {
                val now = System.currentTimeMillis()
                connectTimeMsec = 0L

                GeeksvilleApplication.analytics.track(
                    "connected_seconds",
                    DataPair((now - connectTimeMsec) / 1000.0)
                )
            }

            // Have our timeout fire in the approprate number of seconds
            sleepTimeout = serviceScope.handledLaunch {
                try {
                    // If we have a valid timeout, wait that long (+30 seconds) otherwise, just wait 30 seconds
                    val timeout = (radioConfig?.preferences?.lsSecs ?: 0) + 30

                    debug("Waiting for sleeping device, timeout=$timeout secs")
                    delay(timeout * 1000L)
                    warn("Device timeout out, setting disconnected")
                    onConnectionChanged(ConnectionState.DISCONNECTED)
                } catch (ex: CancellationException) {
                    debug("device sleep timeout cancelled")
                }
            }

            // broadcast an intent with our new connection state
            serviceBroadcasts.broadcastConnection()
        }

        fun startDisconnect() {
            // Just in case the user uncleanly reboots the phone, save now (we normally save in onDestroy)
            saveSettings()

            GeeksvilleApplication.analytics.track(
                "mesh_disconnect",
                DataPair("num_nodes", numNodes),
                DataPair("num_online", numOnlineNodes)
            )
            GeeksvilleApplication.analytics.track("num_nodes", DataPair(numNodes))

            // broadcast an intent with our new connection state
            serviceBroadcasts.broadcastConnection()
        }

        fun startConnect() {
            // Do our startup init
            try {
                connectTimeMsec = System.currentTimeMillis()
                SoftwareUpdateService.sendProgress(this, ProgressNotStarted, true) // Kinda crufty way of reiniting software update
                startConfig()

            } catch (ex: InvalidProtocolBufferException) {
                errormsg(
                    "Invalid protocol buffer sent by device - update device software and try again",
                    ex
                )
            } catch (ex: RadioNotConnectedException) {
                // note: no need to call startDeviceSleep(), because this exception could only have reached us if it was already called
                errormsg("Lost connection to radio during init - waiting for reconnect")
            } catch (ex: RemoteException) {
                // It seems that when the ESP32 goes offline it can briefly come back for a 100ms ish which
                // causes the phone to try and reconnect.  If we fail downloading our initial radio state we don't want to
                // claim we have a valid connection still
                connectionState = ConnectionState.DEVICE_SLEEP
                startDeviceSleep()
                throw ex; // Important to rethrow so that we don't tell the app all is well
            }
        }

        // Cancel any existing timeouts
        sleepTimeout?.let {
            it.cancel()
            sleepTimeout = null
        }

        connectionState = c
        when (c) {
            ConnectionState.CONNECTED ->
                startConnect()
            ConnectionState.DEVICE_SLEEP ->
                startDeviceSleep()
            ConnectionState.DISCONNECTED ->
                startDisconnect()
        }

        // Update the android notification in the status bar
        updateNotification()
    }

    /**
     * Receives messages from our BT radio service and processes them to update our model
     * and send to clients as needed.
     */
    private val radioInterfaceReceiver = object : BroadcastReceiver() {

        // Important to never throw exceptions out of onReceive
        override fun onReceive(context: Context, intent: Intent) = exceptionReporter {
            // NOTE: Do not call handledLaunch here, because it can cause out of order message processing - because each routine is scheduled independently
            // serviceScope.handledLaunch {
            debug("Received broadcast ${intent.action}")
            when (intent.action) {
                RadioInterfaceService.RADIO_CONNECTED_ACTION -> {
                    try {
                        val connected = intent.getBooleanExtra(EXTRA_CONNECTED, false)
                        val permanent = intent.getBooleanExtra(EXTRA_PERMANENT, false)
                        onConnectionChanged(
                            when {
                                connected -> ConnectionState.CONNECTED
                                permanent -> ConnectionState.DISCONNECTED
                                else -> ConnectionState.DEVICE_SLEEP
                            }
                        )
                    } catch (ex: RemoteException) {
                        // This can happen sometimes (especially if the device is slowly dying due to killing power, don't report to crashlytics
                        warn("Abandoning reconnect attempt, due to errors during init: ${ex.message}")
                    }
                }

                RadioInterfaceService.RECEIVE_FROMRADIO_ACTION -> {
                    val bytes = intent.getByteArrayExtra(EXTRA_PAYLOAD)!!
                    try {
                        val proto =
                            MeshProtos.FromRadio.parseFrom(bytes)
                        // info("Received from radio service: ${proto.toOneLineString()}")
                        when (proto.variantCase.number) {
                            MeshProtos.FromRadio.PACKET_FIELD_NUMBER -> handleReceivedMeshPacket(
                                proto.packet
                            )

                            MeshProtos.FromRadio.CONFIG_COMPLETE_ID_FIELD_NUMBER -> handleConfigComplete(
                                proto.configCompleteId
                            )

                            MeshProtos.FromRadio.MY_INFO_FIELD_NUMBER -> handleMyInfo(proto.myInfo)

                            MeshProtos.FromRadio.NODE_INFO_FIELD_NUMBER -> handleNodeInfo(proto.nodeInfo)

                            MeshProtos.FromRadio.RADIO_FIELD_NUMBER -> handleRadioConfig(proto.radio)

                            else -> errormsg("Unexpected FromRadio variant")
                        }
                    } catch (ex: InvalidProtocolBufferException) {
                        errormsg("Invalid Protobuf from radio, len=${bytes.size}", ex)
                    }
                }

                else -> errormsg("Unexpected radio interface broadcast")
            }
        }
    }

    /// A provisional MyNodeInfo that we will install if all of our node config downloads go okay
    private var newMyNodeInfo: MyNodeInfo? = null

    /// provisional NodeInfos we will install if all goes well
    private val newNodes = mutableListOf<MeshProtos.NodeInfo>()

    /// Used to make sure we never get foold by old BLE packets
    private var configNonce = 1


    private fun handleRadioConfig(radio: MeshProtos.RadioConfig) {
        val packetToSave = Packet(
            UUID.randomUUID().toString(),
            "RadioConfig",
            System.currentTimeMillis(),
            radio.toString()
        )
        insertPacket(packetToSave)
        radioConfig = radio
    }

    /**
     * Convert a protobuf NodeInfo into our model objects and update our node DB
     */
    private fun installNodeInfo(info: MeshProtos.NodeInfo) {
        // Just replace/add any entry
        updateNodeInfo(info.num) {
            if (info.hasUser())
                it.user =
                    MeshUser(
                        info.user.id,
                        info.user.longName,
                        info.user.shortName
                    )

            if (info.hasPosition()) {
                // For the local node, it might not be able to update its times because it doesn't have a valid GPS reading yet
                // so if the info is for _our_ node we always assume time is current
                it.position = Position(info.position)
            }
        }
    }

    private fun handleNodeInfo(info: MeshProtos.NodeInfo) {
        debug("Received nodeinfo num=${info.num}, hasUser=${info.hasUser()}, hasPosition=${info.hasPosition()}")

        val packetToSave = Packet(
            UUID.randomUUID().toString(),
            "NodeInfo",
            System.currentTimeMillis(),
            info.toString()
        )
        insertPacket(packetToSave)

        logAssert(newNodes.size <= 256) // Sanity check to make sure a device bug can't fill this list forever
        newNodes.add(info)
    }


    /**
     * Update the nodeinfo (called from either new API version or the old one)
     */
    private fun handleMyInfo(myInfo: MeshProtos.MyNodeInfo) {
        val packetToSave = Packet(
            UUID.randomUUID().toString(),
            "MyNodeInfo",
            System.currentTimeMillis(),
            myInfo.toString()
        )
        insertPacket(packetToSave)

        setFirmwareUpdateFilename(myInfo)

        val mi = with(myInfo) {
            MyNodeInfo(
                myNodeNum,
                hasGps,
                region,
                hwModel,
                firmwareVersion,
                firmwareUpdateFilename != null,
                SoftwareUpdateService.shouldUpdate(
                    this@MeshService,
                    DeviceVersion(firmwareVersion)
                ),
                currentPacketId.toLong() and 0xffffffffL,
                if (nodeNumBits == 0) 8 else nodeNumBits,
                if (packetIdBits == 0) 8 else packetIdBits,
                if (messageTimeoutMsec == 0) 5 * 60 * 1000 else messageTimeoutMsec, // constants from current device code
                minAppVersion
            )
        }

        newMyNodeInfo = mi

        /// Track types of devices and firmware versions in use
        GeeksvilleApplication.analytics.setUserInfo(
            DataPair("region", mi.region),
            DataPair("firmware", mi.firmwareVersion),
            DataPair("has_gps", mi.hasGPS),
            DataPair("hw_model", mi.model),
            DataPair("dev_error_count", myInfo.errorCount)
        )

        if (myInfo.errorCode.number != 0) {
            GeeksvilleApplication.analytics.track(
                "dev_error",
                DataPair("code", myInfo.errorCode.number),
                DataPair("address", myInfo.errorAddress),

                // We also include this info, because it is required to correctly decode address from the map file
                DataPair("firmware", mi.firmwareVersion),
                DataPair("hw_model", mi.model),
                DataPair("region", mi.region)
            )
        }
    }



    /**
     * If we are updating nodes we might need to use old (fixed by firmware build)
     * region info to populate our new universal ROMs.
     *
     * This function updates our saved preferences region info and if the device has an unset new
     * region info, we set it.
     */
    private fun updateRegion() {
        ignoreException {
            // Try to pull our region code from the new preferences field
            // FIXME - do not check net - figuring out why board is rebooting
            val curConfigRegion = radioConfig?.preferences?.region ?: MeshProtos.RegionCode.Unset
            if (curConfigRegion != MeshProtos.RegionCode.Unset) {
                info("Using device region $curConfigRegion (code ${curConfigRegion.number})")
                curRegionValue = curConfigRegion.number
            }

            if (curRegionValue == MeshProtos.RegionCode.Unset_VALUE) {
                // look for a legacy region
                val legacyRegex = Regex(".+-(.+)")
                myNodeInfo?.region?.let { legacyRegion ->
                    val matches = legacyRegex.find(legacyRegion)
                    if (matches != null) {
                        val (region) = matches.destructured
                        val newRegion = MeshProtos.RegionCode.valueOf(region)
                        info("Upgrading legacy region $newRegion (code ${newRegion.number})")
                        curRegionValue = newRegion.number
                    }
                }
            }

            // If nothing was set in our (new style radio preferences, but we now have a valid setting - slam it in)
            if (curConfigRegion == MeshProtos.RegionCode.Unset && curRegionValue != MeshProtos.RegionCode.Unset_VALUE) {
                info("Telling device to upgrade region")

                // Tell the device to set the new region field (old devices will simply ignore this)
                radioConfig?.let { currentConfig ->
                    val newConfig = currentConfig.toBuilder()

                    val newPrefs = currentConfig.preferences.toBuilder()
                    newPrefs.regionValue = curRegionValue
                    newConfig.preferences = newPrefs.build()

                    sendRadioConfig(newConfig.build())
                }
            }
        }
    }

    private fun handleConfigComplete(configCompleteId: Int) {
        if (configCompleteId == configNonce) {

            val packetToSave = Packet(
                UUID.randomUUID().toString(),
                "ConfigComplete",
                System.currentTimeMillis(),
                configCompleteId.toString()
            )
            insertPacket(packetToSave)

            // This was our config request
            if (newMyNodeInfo == null || newNodes.isEmpty())
                errormsg("Did not receive a valid config")
            else {
                debug("Installing new node DB")
                discardNodeDB()
                myNodeInfo = newMyNodeInfo

                newNodes.forEach(::installNodeInfo)
                newNodes.clear() // Just to save RAM ;-)

                haveNodeDB = true // we now have nodes from real hardware
                processEarlyPackets() // send receive any packets that were queued up

                // broadcast an intent with our new connection state
                serviceBroadcasts.broadcastConnection()
                onNodeDBChanged()
                reportConnection()

                updateRegion()
            }
        } else
            warn("Ignoring stale config complete")
    }

    /**
     * Start the modern (REV2) API configuration flow
     */
    private fun startConfig() {
        configNonce += 1
        newNodes.clear()
        newMyNodeInfo = null
        debug("Starting config nonce=$configNonce")

        sendToRadio(ToRadio.newBuilder().apply {
            this.wantConfigId = configNonce
        })
    }

    /**
     * Send a position (typically from our built in GPS) into the mesh.
     * Must be called from serviceScope. Use sendPositionScoped() for direct calls.
     */
    private fun sendPosition(
        lat: Double,
        lon: Double,
        alt: Int,
        destNum: Int = NODENUM_BROADCAST,
        wantResponse: Boolean = false
    ) {
        debug("Sending our position to=$destNum lat=$lat, lon=$lon, alt=$alt")

        val position = MeshProtos.Position.newBuilder().also {
            it.longitudeI = Position.degI(lon)
            it.latitudeI = Position.degI(lat)

            it.altitude = alt
            it.time = currentSecond() // Include our current timestamp
        }.build()

        // encapsulate our payload in the proper protobufs and fire it off
        val packet = newMeshPacketTo(destNum)

        packet.decoded = MeshProtos.SubPacket.newBuilder().also {
            val isNewPositionAPI = deviceVersion >= DeviceVersion("1.20.0") // We changed position APIs with this version
            if (isNewPositionAPI) {
                // Use the new position as data format
                it.data = makeData(Portnums.PortNum.POSITION_APP_VALUE, position.toByteString())
            } else {
                // Send the old dedicated position subpacket
                it.position = position
            }
            it.wantResponse = wantResponse
        }.build()

        // Also update our own map for our nodenum, by handling the packet just like packets from other users
        handleReceivedPosition(myNodeInfo!!.myNodeNum, position)

        // send the packet into the mesh
        sendToRadio(packet.build())
    }

    private fun sendPositionScoped(
        lat: Double,
        lon: Double,
        alt: Int,
        destNum: Int = NODENUM_BROADCAST,
        wantResponse: Boolean = false
    ) = serviceScope.handledLaunch {
        try {
            sendPosition(lat, lon, alt, destNum, wantResponse)
        }
        catch(ex: RadioNotConnectedException) {
            warn("Ignoring disconnected radio during gps location update")
        }
    }

    /** Send our current radio config to the device
     */
    private fun sendRadioConfig(c: MeshProtos.RadioConfig) {
        // Update our device
        sendToRadio(ToRadio.newBuilder().apply {
            this.setRadio = c
        })

        // Update our cached copy
        this@MeshService.radioConfig = c
    }

    /** Set our radio config
     */
    private fun setRadioConfig(payload: ByteArray) {
        val parsed = MeshProtos.RadioConfig.parseFrom(payload)

        sendRadioConfig(parsed)
    }

    /**
     * Set our owner with either the new or old API
     */
    fun setOwner(myId: String?, longName: String, shortName: String) {
        val myNode = myNodeInfo
        if (myNode != null) {


            val myInfo = toNodeInfo(myNode.myNodeNum)
            if (longName == myInfo.user?.longName && shortName == myInfo.user?.shortName)
                debug("Ignoring nop owner change")
            else {
                debug("SetOwner $myId : ${longName.anonymize} : $shortName")

                val user = MeshProtos.User.newBuilder().also {
                    if (myId != null)  // Only set the id if it was provided
                        it.id = myId
                    it.longName = longName
                    it.shortName = shortName
                }.build()

                // Also update our own map for our nodenum, by handling the packet just like packets from other users

                handleReceivedUser(myNode.myNodeNum, user)

                // set my owner info
                sendToRadio(ToRadio.newBuilder().apply {
                    this.setOwner = user
                })
            }
        } else
            throw Exception("Can't set user without a node info") // this shouldn't happen
    }

    /// Do not use directly, instead call generatePacketId()
    private var currentPacketId = 0L

    /**
     * Generate a unique packet ID (if we know enough to do so - otherwise return 0 so the device will do it)
     */
    private fun generatePacketId(): Int {

        myNodeInfo?.let {
            val numPacketIds =
                ((1L shl it.packetIdBits) - 1).toLong() // A mask for only the valid packet ID bits, either 255 or maxint

            if (currentPacketId == 0L) {
                logAssert(it.packetIdBits == 8 || it.packetIdBits == 32) // Only values I'm expecting (though we don't require this)

                val devicePacketId = if (it.currentPacketId == 0L) {
                    // Old devices don't send their current packet ID, in that case just pick something random and it will probably be fine ;-)
                    val random = Random(System.currentTimeMillis())
                    random.nextLong().absoluteValue
                } else
                    it.currentPacketId

                // Not inited - pick a number on the opposite side of what the device is using
                currentPacketId = devicePacketId + numPacketIds / 2
            } else {
                currentPacketId++
            }

            currentPacketId = currentPacketId and 0xffffffff // keep from exceeding 32 bits

            // Use modulus and +1 to ensure we skip 0 on any values we return
            return ((currentPacketId % numPacketIds) + 1L).toInt()
        }

        return 0 // We don't have mynodeinfo yet, so just let the radio eventually assign an ID
    }

    var firmwareUpdateFilename: UpdateFilenames? = null

    /***
     * Return the filename we will install on the device
     */
    private fun setFirmwareUpdateFilename(info: MeshProtos.MyNodeInfo) {
        firmwareUpdateFilename = try {
            if (info.region != null && info.firmwareVersion != null && info.hwModel != null)
                SoftwareUpdateService.getUpdateFilename(
                    this,
                    info.hwModel
                )
            else
                null
        } catch (ex: Exception) {
            errormsg("Unable to update", ex)
            null
        }

        debug("setFirmwareUpdateFilename $firmwareUpdateFilename")
    }

    /// We only allow one update to be running at a time
    private var updateJob: Job? = null

    private fun doFirmwareUpdate() {
        // Run in the IO thread
        val filename = firmwareUpdateFilename ?: throw Exception("No update filename")
        val safe =
            BluetoothInterface.safe
                ?: throw Exception("Can't update - no bluetooth connected")

        if (updateJob?.isActive == true)
            throw Exception("Firmware update already running")
        else
            updateJob = serviceScope.handledLaunch {
                info("Starting firmware update coroutine")
                SoftwareUpdateService.doUpdate(this@MeshService, safe, filename)
            }
    }

    /**
     * Remove any sent packets that have been sitting around too long
     *
     * Note: we give each message what the timeout the device code is using, though in the normal
     * case the device will fail after 3 retries much sooner than that (and it will provide a nak to us)
     */
    private fun deleteOldPackets() {
        myNodeInfo?.apply {
            val now = System.currentTimeMillis()

            val old = sentPackets.values.filter { p ->
                (p.status == MessageStatus.ENROUTE && p.time + messageTimeoutMsec < now)
            }

            // Do this using a separate list to prevent concurrent modification exceptions
            old.forEach { p ->
                handleAckNak(false, p.id)
            }
        }
    }


    private fun enqueueForSending(p: DataPacket) {
        p.status = MessageStatus.QUEUED
        offlineSentPackets.add(p)
    }

    val binder = object : IMeshService.Stub() {

        override fun setDeviceAddress(deviceAddr: String?) = toRemoteExceptions {
            debug("Passing through device change to radio service: ${deviceAddr.anonymize}")

            val res = radio.service.setDeviceAddress(deviceAddr)
            if (res) {
                discardNodeDB()
            }
            res
        }

        // Note: bound methods don't get properly exception caught/logged, so do that with a wrapper
        // per https://blog.classycode.com/dealing-with-exceptions-in-aidl-9ba904c6d63
        override fun subscribeReceiver(packageName: String, receiverName: String) =
            toRemoteExceptions {
                clientPackages[receiverName] = packageName
            }

        override fun getOldMessages(): MutableList<DataPacket> {
            return recentDataPackets
        }

        override fun getUpdateStatus(): Int = SoftwareUpdateService.progress

        override fun startFirmwareUpdate() = toRemoteExceptions {
            doFirmwareUpdate()
        }

        override fun getMyNodeInfo(): MyNodeInfo = toRemoteExceptions {
            this@MeshService.myNodeInfo ?: throw RadioNotConnectedException("No MyNodeInfo")
        }

        override fun getMyId() = toRemoteExceptions { myNodeID }

        override fun setOwner(myId: String?, longName: String, shortName: String) =
            toRemoteExceptions {
                this@MeshService.setOwner(myId, longName, shortName)
            }

        override fun send(p: DataPacket) {
            toRemoteExceptions {
                // Init from and id
                myNodeID?.let { myId ->
                    if (p.from == DataPacket.ID_LOCAL)
                        p.from = myId

                    if (p.id == 0)
                        p.id = generatePacketId()
                }

                info("sendData dest=${p.to}, id=${p.id} <- ${p.bytes!!.size} bytes (connectionState=$connectionState)")

                // Keep a record of datapackets, so GUIs can show proper chat history
                rememberDataPacket(p)

                if (p.bytes.size >= MeshProtos.Constants.DATA_PAYLOAD_LEN.number) {
                    p.status = MessageStatus.ERROR
                    throw RemoteException("Message too long")
                }

                if (p.id != 0) { // If we have an ID we can wait for an ack or nak
                    deleteOldPackets()
                    sentPackets[p.id] = p
                }

                // If radio is sleeping or disconnected, queue the packet
                when (connectionState) {
                    ConnectionState.CONNECTED ->
                        try {
                            sendNow(p)
                        } catch (ex: Exception) {
                            // This can happen if a user is unlucky and the device goes to sleep after the GUI starts a send, but before we update connectionState
                            errormsg("Error sending message, so enqueueing", ex)
                            enqueueForSending(p)
                        }
                    else -> // sleeping or disconnected
                        enqueueForSending(p)
                }

                GeeksvilleApplication.analytics.track(
                    "data_send",
                    DataPair("num_bytes", p.bytes.size),
                    DataPair("type", p.dataType)
                )

                GeeksvilleApplication.analytics.track(
                    "num_data_sent",
                    DataPair(1)
                )
            }
        }

        override fun getRadioConfig(): ByteArray = toRemoteExceptions {
            this@MeshService.radioConfig?.toByteArray()
                ?: throw RadioNotConnectedException()
        }

        override fun setRadioConfig(payload: ByteArray) = toRemoteExceptions {
            this@MeshService.setRadioConfig(payload)
        }

        override fun getNodes(): MutableList<NodeInfo> = toRemoteExceptions {
            val r = nodeDBbyID.values.toMutableList()
            info("in getOnline, count=${r.size}")
            // return arrayOf("+16508675309")
            r
        }

        override fun connectionState(): String = toRemoteExceptions {
            val r = this@MeshService.connectionState
            info("in connectionState=$r")
            r.toString()
        }
    }
}

fun updateNodeInfoTime(it: NodeInfo, rxTime: Int) {
    if (it.position?.time == null || it.position?.time!! < rxTime)
        it.position = it.position?.copy(time = rxTime)
}
