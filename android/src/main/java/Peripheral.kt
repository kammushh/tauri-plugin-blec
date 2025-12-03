import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import com.plugin.blec.BleClientPlugin
import org.json.JSONArray
import java.util.Base64
import java.util.UUID


class Peripheral(private val activity: Activity, private val device: BluetoothDevice, private val plugin: BleClientPlugin) {
    private val CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val base64Encoder: Base64.Encoder = Base64.getEncoder()

    private var connected = false
    private var bonded = false
    private var gatt: BluetoothGatt? = null
    private var services: List<BluetoothGattService> = listOf()
    private val characteristics: MutableMap<Pair<UUID,UUID>,BluetoothGattCharacteristic> = mutableMapOf()
    private var onConnectionStateChange: ((connected:Boolean,error:String)->Unit)? = null
    private var onServicesDiscovered: ((connected:Boolean,error:String)->Unit)? = null
    private var notifyChannel:Channel? = null
    private val onReadInvoke:MutableMap<UUID,Invoke> = mutableMapOf()
    private val onWriteInvoke:MutableMap<UUID,Invoke> = mutableMapOf()
    private var onDescriptorInvoke: Invoke? = null
    private var onMtuInvoke: Invoke? = null
    private var currentMtu = 517
    private var isCleaningUp = false  // Prevent multiple cleanup calls

    private enum class Event{
        DeviceConnected,
        DeviceDisconnected
    }
    
    private fun sendEvent(event: Event){
        val channel = this.plugin.eventChannel?: return
        val data = JSObject()
        if (event == Event.DeviceConnected){
            data.put("DeviceConnected",this.device.address)
        } else if (event == Event.DeviceDisconnected){
            data.put("DeviceDisconnected",this.device.address)
        }
        Log.d("Peripheral", "Sending event: $data")
        channel.send(data)
    }

    private val callback = object:BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d("Peripheral", "onConnectionStateChange: status=$status, newState=$newState, device=${device.address}")
            
            // Prevent multiple cleanup calls
            if (isCleaningUp) {
                Log.d("Peripheral", "Already cleaning up, ignoring callback")
                return
            }
            
            when {
                // SUCCESS PATH - Connection established
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED && gatt != null -> {
                    Log.d("Peripheral", "Connection successful")
                    this@Peripheral.connected = true
                    this@Peripheral.gatt = gatt
                    this@Peripheral.onConnectionStateChange?.invoke(true, "")
                    this@Peripheral.sendEvent(Event.DeviceConnected)
                }
                
                // DISCONNECTION PATH - Any disconnection (normal or error)
                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    val statusMsg = when (status) {
                        BluetoothGatt.GATT_SUCCESS -> "Normal disconnection"
                        133 -> "GATT Error 133 - Device unavailable or out of range"
                        4 -> "GATT Error 4 - Connection timeout"
                        8 -> "GATT Error 8 - Connection timeout (alternative)"
                        19 -> "GATT Error 19 - Connection terminated by peer"
                        22 -> "GATT Error 22 - Link loss"
                        else -> "Disconnected with status: $status"
                    }
                    
                    Log.d("Peripheral", "Device disconnected: $statusMsg")
                    
                    // CRITICAL: Close GATT to release Android BLE resources
                    isCleaningUp = true
                    gatt?.close()
                    
                    this@Peripheral.connected = false
                    this@Peripheral.gatt = null
                    
                    val errorMessage = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Disconnected normally"
                    } else {
                        "Connection lost: $statusMsg"
                    }
                    
                    this@Peripheral.onConnectionStateChange?.invoke(false, errorMessage)
                    this@Peripheral.sendEvent(Event.DeviceDisconnected)
                    
                    // Reset cleanup flag after a delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        isCleaningUp = false
                    }, 500)
                }
                
                // ERROR PATH - Connection failed but not disconnected yet
                // This is the CRITICAL bug fix for Redmi/MIUI phones
                else -> {
                    Log.e("Peripheral", "Connection error: status=$status, state=$newState")
                    
                    // MUST close GATT immediately to prevent resource leak
                    // Without this, Redmi phones exhaust BLE GATT clients
                    isCleaningUp = true
                    gatt?.close()
                    
                    this@Peripheral.connected = false
                    this@Peripheral.gatt = null
                    
                    val errorMessage = when (status) {
                        133 -> "Connection failed: Device unavailable (Error 133)"
                        4 -> "Connection failed: Timeout (Error 4)"
                        8 -> "Connection failed: Timeout (Error 8)"
                        19 -> "Connection failed: Terminated by device (Error 19)"
                        257 -> "Connection failed: Too many BLE clients (Error 257)"
                        else -> "Connection failed with status: $status, state: $newState"
                    }
                    
                    Log.e("Peripheral", errorMessage)
                    this@Peripheral.onConnectionStateChange?.invoke(false, errorMessage)
                    this@Peripheral.sendEvent(Event.DeviceDisconnected)
                    
                    // Reset cleanup flag after a delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        isCleaningUp = false
                    }, 500)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("Peripheral", "onServicesDiscovered: status=$status, services=${gatt.services.size}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                this@Peripheral.services = listOf()
                this@Peripheral.onServicesDiscovered?.invoke(
                    false,
                    "No services discovered. Status $status"
                )
            } else {
                this@Peripheral.services = gatt.services
                for (s in gatt.services) {
                    Log.d("Peripheral", "Service found: ${s.uuid}")
                    for (c in s.characteristics) {
                        this@Peripheral.characteristics[Pair(c.uuid,c.service.uuid)] = c
                        Log.d("Peripheral", "  Characteristic: ${c.uuid}, properties: ${c.properties}")
                    }
                }
                this@Peripheral.onServicesDiscovered?.invoke(true, "")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            this@Peripheral.notifyChannel?.let {
                synchronized(it) {
                    val notification = JSObject()
                    notification.put("uuid", characteristic.uuid)
                    notification.put("serviceUuid", characteristic.service.uuid)
                    notification.put("data", base64Encoder.encodeToString(value))
                    it.send(notification)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val id = characteristic?.uuid ?: return
            synchronized(this@Peripheral.onWriteInvoke) {
                val invoke = this@Peripheral.onWriteInvoke[id]
                if (invoke == null) {
                    Log.e("Peripheral", "Did not find tauri invoke obj for write on $id")
                } else {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        invoke.reject("Write to characteristic $id failed with status $status")
                    } else {
                        invoke.resolve()
                    }
                }
                this@Peripheral.onWriteInvoke.remove(id)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val id = characteristic.uuid ?: return
            synchronized(this@Peripheral.onReadInvoke) {
                val invoke = this@Peripheral.onReadInvoke[id]
                if (invoke == null) {
                    Log.e("Peripheral", "Did not find tauri invoke obj for read on $id")
                } else {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        invoke.reject("Read from characteristic $id failed with status $status")
                    } else {
                        val res = JSObject()
                        res.put("value", base64Encoder.encodeToString(value))
                        invoke.resolve(res)
                    }
                }
                this@Peripheral.onReadInvoke.remove(id)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                this@Peripheral.onDescriptorInvoke?.reject("descriptor write failed with status: $status")
            } else if (descriptor?.uuid != CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR) {
                this@Peripheral.onDescriptorInvoke?.reject("unexpected write to descriptor: ${descriptor?.uuid}")
            } else {
                this@Peripheral.onDescriptorInvoke?.resolve()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d("Peripheral", "MTU changed to $mtu with status $status")
            currentMtu = mtu
            if (status != BluetoothGatt.GATT_SUCCESS) {
                this@Peripheral.onMtuInvoke?.reject("mtu change failed: $status")
            } else {
                val res = JSObject()
                res.put("mtu", mtu)
                this@Peripheral.onMtuInvoke?.resolve(res)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(invoke:Invoke) {
        Log.d("Peripheral", "connect() called for device: ${device.address}")
        
        // Check if already connected
        if (this.connected && this.gatt != null) {
            Log.w("Peripheral", "Already connected to ${device.address}")
            invoke.reject("Already connected to this device")
            return
        }
        
        this.onConnectionStateChange = { success, error ->
            if(success){
                Log.d("Peripheral", "Connection callback: SUCCESS")
                invoke.resolve()
            } else {
                Log.e("Peripheral", "Connection callback: FAILED - $error")
                invoke.reject(error)
            }
            this@Peripheral.onConnectionStateChange = null
        }
        
        // Attempt connection with no auto-connect (false)
        // This is important for reliability on MIUI/Redmi devices
        this.device.connectGatt(activity, false, this.callback)
    }

    @SuppressLint("MissingPermission")
    fun discoverServices(invoke:Invoke){
        val gatt = this.gatt
        if (gatt == null){
            invoke.reject("No gatt server connected")
            return
        }
        
        this.onServicesDiscovered = { success, error ->
            if (success) {
                Log.d("Peripheral", "Service discovery successful")
                invoke.resolve()
            } else {
                Log.e("Peripheral", "Service discovery failed: $error")
                invoke.reject(error)
            }
            this.onServicesDiscovered = null
        }

        Handler(Looper.getMainLooper()).post {
            if (!gatt.discoverServices()) {
                invoke.reject("Failed to start service discovery")
            } else {
                Log.d("Peripheral", "Service discovery started")
            }
        }
    }

    fun isConnected():Boolean {
        return this.connected
    }

    @SuppressLint("MissingPermission")
    fun isBonded(): Boolean {
        return this.device.bondState == BluetoothDevice.BOND_BONDED
    }

    @SuppressLint("MissingPermission")
    fun disconnect(invoke: Invoke) {
        Log.d("Peripheral", "disconnect() called explicitly for ${device.address}")
        
        // Prevent multiple disconnect calls
        if (isCleaningUp) {
            Log.d("Peripheral", "Already disconnecting, ignoring")
            invoke.resolve()
            return
        }
        
        isCleaningUp = true
        
        // Clear all callbacks immediately to prevent races
        this.onConnectionStateChange = null
        this.onServicesDiscovered = null
        this.onDescriptorInvoke = null
        this.onMtuInvoke = null
        this.onReadInvoke.clear()
        this.onWriteInvoke.clear()
        
        // Disconnect and close GATT
        val currentGatt = this.gatt
        if (currentGatt != null) {
            try {
                currentGatt.disconnect()
                Log.d("Peripheral", "GATT disconnect() called")
            } catch (e: Exception) {
                Log.e("Peripheral", "Error during disconnect: ${e.message}")
            }
            
            // CRITICAL: Close after delay to allow disconnect to complete
            // Increased delay for MIUI/Redmi reliability
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    currentGatt.close()
                    Log.d("Peripheral", "GATT close() called - resources released")
                } catch (e: Exception) {
                    Log.e("Peripheral", "Error during close: ${e.message}")
                }
                
                this@Peripheral.gatt = null
                this@Peripheral.connected = false
                this@Peripheral.services = listOf()
                this@Peripheral.characteristics.clear()
                
                // Reset cleanup flag
                Handler(Looper.getMainLooper()).postDelayed({
                    isCleaningUp = false
                    Log.d("Peripheral", "Disconnect cleanup complete")
                }, 100)
                
                invoke.resolve()
            }, 250)  // 250ms delay for MIUI compatibility
        } else {
            Log.d("Peripheral", "No GATT connection to disconnect")
            this.connected = false
            isCleaningUp = false
            invoke.resolve()
        }
    }

    class ResCharacteristic (
        private val uuid: String,
        private val properties: Int,
        private val descriptors: List<String>
    ){
        fun toJson():JSObject{
            val ret = JSObject()
            ret.put("uuid",uuid)
            ret.put("properties",properties)
            val descriptors = JSONArray()
            for (desc in this.descriptors){
                descriptors.put(desc)
            }
            ret.put("descriptors",descriptors)
            return ret
        }
    }

    class ResService (
        private val uuid: String,
        private val primary: Boolean,
        private val characs: List<ResCharacteristic>,
    ){
        fun toJson():JSObject{
            val ret = JSObject()
            ret.put("uuid",uuid)
            ret.put("primary",primary)
            val characs = JSONArray()
            for (char in this.characs){
                characs.put(char.toJson())
            }
            ret.put("characs",characs)
            return ret
        }
    }

    fun services(invoke:Invoke){
        val services = JSONArray()
        for(service in this.services){
            val characs:MutableList<ResCharacteristic> = mutableListOf()
            for (charac in service.characteristics){
                characs.add(ResCharacteristic(
                    charac.uuid.toString(),
                    charac.properties,
                    charac.descriptors.map { desc ->  desc.uuid.toString()},
                ))
            }
            services.put(ResService(
                service.uuid.toString(),
                service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
                characs
            ).toJson())
        }
        val res = JSObject()
        res.put("result",services)
        invoke.resolve(res)
    }

    fun setNotifyChannel(channel: Channel){
        this.notifyChannel = channel
    }

    @SuppressLint("MissingPermission")
    fun write(invoke: Invoke){
        val args = invoke.parseArgs(BleClientPlugin.WriteParams::class.java)
        val gatt = this.gatt
        if (gatt == null){
            invoke.reject("No gatt server connected")
            return
        }
        val charac = this.characteristics[Pair(args.characteristic!!,args.service!!)]
        if (charac == null){
            invoke.reject("Characteristic ${args.characteristic} not found")
            return
        }
        synchronized(this.onWriteInvoke) {
            if (this.onWriteInvoke[args.characteristic] != null) {
                this.onWriteInvoke[args.characteristic]!!.reject("write was overwritten before finishing")
            }
            this.onWriteInvoke[args.characteristic] = invoke
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                charac,
                args.data!!,
                if (args.withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
            )
        } else {
            @Suppress("DEPRECATION")
            charac.value = args.data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(charac)
        }
    }

    @SuppressLint("MissingPermission")
    fun read(invoke: Invoke){
        val args = invoke.parseArgs(BleClientPlugin.ReadParams::class.java)
        val gatt = this.gatt
        if (gatt == null){
            invoke.reject("No gatt server connected")
            return
        }
        synchronized(this.onReadInvoke) {
            if (this.onReadInvoke[args.characteristic!!] != null) {
                this.onReadInvoke[args.characteristic]!!.reject("read was overwritten before finishing")
            }
            this.onReadInvoke[args.characteristic] = invoke
        }
        val charac = this.characteristics[Pair(args.characteristic!!,args.service!!)]
        if (charac == null){
            invoke.reject("Characteristic ${args.characteristic} not found")
            return
        }
        gatt.readCharacteristic(charac)
    }

    @SuppressLint("MissingPermission")
    fun subscribe(invoke: Invoke, enabled: Boolean){
        val args = invoke.parseArgs(BleClientPlugin.ReadParams::class.java)
        val gatt = this.gatt
        if (gatt == null){
            invoke.reject("No gatt server connected")
            return
        }
        val charac = this.characteristics[Pair(args.characteristic!!,args.service!!)]
        if (charac == null){
            invoke.reject("Characteristic ${args.characteristic} not found")
            return
        }

        if (!gatt.setCharacteristicNotification(charac, enabled)){
            invoke.reject("Failed to set notification status")
            return
        }
        
        this.onDescriptorInvoke = invoke
        val descriptor: BluetoothGattDescriptor = charac.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR)
        val data = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, data)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = data
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestMtu(invoke: Invoke, mtu: Int){
        onMtuInvoke = invoke
        val gatt = this.gatt
        if (gatt == null) {
            invoke.reject("No gatt server connected")
            return
        }
        Handler(Looper.getMainLooper()).post {
            if (!gatt.requestMtu(mtu)) {
                invoke.reject("Failed to request mtu")
            }
        }
    }
}