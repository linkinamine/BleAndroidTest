package com.mitte.bletest.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.mitte.bleconnectivity.*
import com.mitte.bletest.R
import com.mitte.bletest.extension.hideKeyboard
import com.mitte.bletest.extension.showKeyboard
import com.mitte.bletest.extension.toBondStateDescription
import kotlinx.android.synthetic.main.activity_ble_operations.*
import org.jetbrains.anko.alert
import org.jetbrains.anko.noButton
import org.jetbrains.anko.selector
import org.jetbrains.anko.yesButton
import java.text.SimpleDateFormat
import java.util.*

class DeviceOperationsActivity : AppCompatActivity() {
    private lateinit var device: BluetoothDevice
    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private var randomStrings = arrayListOf<String>()


    private lateinit var connectionManagerService: ConnectionManagerService
    private lateinit var characteristics: List<BluetoothGattCharacteristic>
    private lateinit var characteristicProperties: Map<BluetoothGattCharacteristic, List<CharacteristicProperty>>
    private lateinit var characteristicAdapter: CharacteristicAdapter

    var isBound = false

    private val myConnection = object : ServiceConnection {
        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            val binder = service as ConnectionManagerService.ConnectionManagerBinder

            connectionManagerService = binder.getService()

            isBound = true

            connectionManagerService.registerListener(connectionEventListener)

            connectionManagerService.listenToBondStateChanges(this@DeviceOperationsActivity)

            characteristics = getServices()

            characteristicProperties = characteristics.map { characteristic ->
                characteristic to mutableListOf<CharacteristicProperty>().apply {
                    if (characteristic.isNotifiable()) add(CharacteristicProperty.Notifiable)
                    if (characteristic.isIndicatable()) add(CharacteristicProperty.Indicatable)
                    if (characteristic.isReadable()) add(CharacteristicProperty.Readable)
                    if (characteristic.isWritable()) add(CharacteristicProperty.Writable)
                    if (characteristic.isWritableWithoutResponse()) {
                        add(CharacteristicProperty.WritableWithoutResponse)
                    }
                }.toList()
            }.toMap()

            characteristicAdapter = CharacteristicAdapter(characteristics) { characteristic ->
                showCharacteristicOptions(characteristic)
            }

            runOnUiThread { setupRecyclerView()}

        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            connectionManagerService.unregisterListener(connectionEventListener)
            connectionManagerService.teardownConnection(device)

        }
    }


    private fun getServices(): List<BluetoothGattCharacteristic> {
        return ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }

    private var notifyingCharacteristics = mutableListOf<UUID>()

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")

        val intent = Intent(this, ConnectionManagerService::class.java)
        bindService(intent, myConnection, Context.BIND_AUTO_CREATE)

        setContentView(R.layout.activity_ble_operations)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.ble_playground)
        }

        request_mtu_button.setOnClickListener {
            if (mtu_field.text.isNotEmpty() && mtu_field.text.isNotBlank()) {
                mtu_field.text.toString().toIntOrNull()?.let { mtu ->
                    log("Requesting for MTU value of $mtu")
                    connectionManagerService?.requestMtu(device, mtu)
                } ?: log("Invalid MTU value: ${mtu_field.text}")
            } else {
                log("Please specify a numeric value for desired ATT MTU (23-517)")
            }
            this@DeviceOperationsActivity.hideKeyboard()
        }
        log("bond state: ${device.bondState.toBondStateDescription()}")
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            bond_button.text = "Bond"
            bond_button.isEnabled = true
            bond_button.setOnClickListener { device.createBond() }
            this@DeviceOperationsActivity.hideKeyboard()

        } else {
            bond_button.text = "Bonded"
            bond_button.isEnabled = false
            this@DeviceOperationsActivity.hideKeyboard()
        }
    }

    /*   override fun onDestroy() {
           connectionManagerService.unregisterListener(connectionEventListener)
           connectionManagerService.teardownConnection(device)
           super.onDestroy()
       }*/

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        characteristics_recycler_view.apply {
            adapter = characteristicAdapter
            layoutManager = LinearLayoutManager(
                this@DeviceOperationsActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }

        val animator = characteristics_recycler_view.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        runOnUiThread {
            val currentLogText = if (log_text_view.text.isEmpty()) {
                "Beginning of log."
            } else {
                log_text_view.text
            }
            log_text_view.text = "$currentLogText\n$formattedMessage"
            log_scroll_view.post { log_scroll_view.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showCharacteristicOptions(characteristic: BluetoothGattCharacteristic) {
        characteristicProperties[characteristic]?.let { properties ->
            selector("Select an action to perform", properties.map { it.action }) { _, i ->
                when (properties[i]) {
                    CharacteristicProperty.Readable -> {
                        log("Reading from ${characteristic.uuid}")
                        connectionManagerService.readCharacteristic(device, characteristic)
                    }
                    CharacteristicProperty.Writable, CharacteristicProperty.WritableWithoutResponse -> {
                        showWritePayloadDialog(characteristic)
                    }
                    CharacteristicProperty.Notifiable, CharacteristicProperty.Indicatable -> {
                        if (notifyingCharacteristics.contains(characteristic.uuid)) {
                            log("Disabling notifications on ${characteristic.uuid}")
                            connectionManagerService.disableNotifications(device, characteristic)
                        } else {
                            log("Enabling notifications on ${characteristic.uuid}")
                            connectionManagerService.enableNotifications(device, characteristic)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showWritePayloadDialog(characteristic: BluetoothGattCharacteristic) {
        val hexField = layoutInflater.inflate(R.layout.edittext_hex_payload, null) as EditText
        alert {
            customView = hexField
            isCancelable = false
            yesButton {

                with(hexField.text.toString()) {
                    if (isNotBlank() && isNotEmpty()) {
                        randomStrings = generateRandomBytes(this.toInt())

                        connectionManagerService.writeCharacteristic(
                            device,
                            characteristic,
                            randomStrings.first().toByteArray()
                        )

                    } else {
                        log("Please enter the number of strings to write to ${characteristic.uuid}")
                    }
                }

            }
            noButton {}
        }.show()
        hexField.showKeyboard(this)
    }

    private fun generateRandomBytes(numberOfStrings: Int): ArrayList<String> {
        var stringsArray = arrayListOf<String>()
        val chars = ('a'..'z')
        for (i in 0 until numberOfStrings) {
            fun randomString(): String = List(4) { chars.random() }.joinToString("")
            stringsArray.add(randomString())
        }
        return stringsArray
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected from device."
                        positiveButton("OK") { onBackPressed() }
                    }.show()
                }
            }

            onCharacteristicRead = { _, characteristic ->
                log("Read from ${characteristic.uuid}: ${characteristic.value.toReadableString()}")
                randomStrings.removeAt(0)
                if (randomStrings.size > 0) {
                    connectionManagerService.writeCharacteristic(
                        device,
                        characteristic,
                        randomStrings.first().toByteArray()
                    )
                }
            }

            onCharacteristicWrite = { _, characteristic ->
                log("wrote to ${characteristic.uuid}: ${characteristic.value.toReadableString()}")
                connectionManagerService.readCharacteristic(device, characteristic)
            }

            onMtuChanged = { _, mtu ->
                log("MTU updated to $mtu")
            }

            onCharacteristicChanged = { _, characteristic ->
                log("Value changed on ${characteristic.uuid}: ${characteristic.value.toReadableString()}")
            }

            onNotificationsEnabled = { _, characteristic ->
                log("Enabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.add(characteristic.uuid)
            }

            onNotificationsDisabled = { _, characteristic ->
                log("Disabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }


}
