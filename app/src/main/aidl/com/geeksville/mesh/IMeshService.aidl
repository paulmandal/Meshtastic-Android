// com.geeksville.mesh.IMeshService.aidl
package com.geeksville.mesh;

// Declare any non-default types here with import statements
parcelable DataPacket;
parcelable NodeInfo;
parcelable MyNodeInfo;

/**
This is the public android API for talking to meshtastic radios.

To connect to meshtastic you should bind to it per https://developer.android.com/guide/components/bound-services

The intent you use to reach the service should look like this:

        val intent = Intent().apply {
            setClassName(
                "com.geeksville.mesh",
                "com.geeksville.mesh.service.MeshService"
            )
        }

Once you have bound to the service you should register your broadcast receivers per https://developer.android.com/guide/components/broadcasts#context-registered-receivers

    // com.geeksville.mesh.x broadcast intents, where x is:
    // RECEIVED_DATA  for data received from other nodes.  payload will contain a DataPacket
    // NODE_CHANGE  for new IDs appearing or disappearing
    // CONNECTION_CHANGED for losing/gaining connection to the packet radio
    // MESSAGE_STATUS_CHANGED for any message status changes (for sent messages only, other messages come via RECEIVED_DATA.  payload will contain a message ID and a MessageStatus)

At the very least you will probably want to receive RECEIVED_DATA.

Note - these calls might throw RemoteException to indicate mesh error states
*/
interface IMeshService {
    /// Tell the service where to send its broadcasts of received packets
    /// This call is only required for manifest declared receivers.  If your receiver is context-registered
    /// you don't need this.
    void subscribeReceiver(String packageName, String receiverName);

    /**
    * Set the ID info for this node

    If myId is null, then the existing unique node ID is preserved, only the human visible longName/shortName is changed
    */
    void setOwner(String myId, String longName, String shortName);

    /// Return my unique user ID string
    String getMyId();

    /*
    Send a packet to a specified node name

    typ is defined in mesh.proto Data.Type.  For now juse use 0 to mean opaque bytes.

    destId can be null to indicate "broadcast message"

    messageStatus and id of the provided message will be updated by this routine to indicate
    message send status and the ID that can be used to locate the message in the future
    */
    void send(inout DataPacket packet);

    /**
    Get the IDs of everyone on the mesh.  You should also subscribe for NODE_CHANGE broadcasts.
    */
    List<NodeInfo> getNodes();

    /// This method is only intended for use in our GUI, so the user can set radio options
    /// It returns a RadioConfig protobuf.
    byte []getRadioConfig();

    /// Return an list of MeshPacket protobuf (byte arrays) which were received while your client app was offline (recent messages only).
    /// Also includes any messages we have sent recently (useful for finding current message status)
    List<DataPacket> getOldMessages();

    /// This method is only intended for use in our GUI, so the user can set radio options
    /// It sets a RadioConfig protobuf
    void setRadioConfig(in byte []payload);

    /**
    Is the packet radio currently connected to the phone?  Returns a ConnectionState string.
    */
    String connectionState();

    /// If a macaddress we will try to talk to our device, if null we will be idle.
    /// Any current connection will be dropped (even if the device address is the same) before reconnecting.
    /// Users should not call this directly, only used internally by the MeshUtil activity
    /// Returns true if the device address actually changed, or false if no change was needed
    boolean setDeviceAddress(String deviceAddr);

    /// Get basic device hardware info about our connected radio.  Will never return NULL.  Will throw
    /// RemoteException if no my node info is available
    MyNodeInfo getMyNodeInfo();

    /// Start updating the radios firmware
    void startFirmwareUpdate();

    /**
    Return a number 0-100 for progress. -1 for completed and success, -2 for failure
    */
    int getUpdateStatus();
}
