package com.czterysery.ledcontroller.view

import android.bluetooth.BluetoothAdapter
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.czterysery.ledcontroller.BluetoothStateBroadcastReceiver
import com.czterysery.ledcontroller.DialogManager
import com.czterysery.ledcontroller.R
import com.czterysery.ledcontroller.data.bluetooth.BluetoothController
import com.czterysery.ledcontroller.data.model.*
import com.czterysery.ledcontroller.data.socket.SocketManagerImpl
import com.czterysery.ledcontroller.presenter.MainPresenter
import com.czterysery.ledcontroller.presenter.MainPresenterImpl
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.row_spn.*
import org.jetbrains.anko.textColor
import org.jetbrains.anko.toast
import top.defaults.colorpicker.ColorObserver

const val REQUEST_ENABLE_BT = 1

class MainActivity : AppCompatActivity(), MainView, ColorObserver {
    private lateinit var dialogManager: DialogManager
    private val btStateReceiver = BluetoothStateBroadcastReceiver()
    private val mPresenter: MainPresenter = MainPresenterImpl(
            btStateReceiver,
            BluetoothController(),
            SocketManagerImpl()
    )

    private var allowChangeColor = false
    private var previousConnectionState: ConnectionState = Disconnected

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dialogManager = DialogManager(this)

        initColorPicker()
        initAnimSpinner()

        brightnessSlider.setOnPositionChangeListener { _, _, _, _, _, newValue ->
            mPresenter.setBrightness(newValue)
        }

        connectAction.setOnClickListener {
            changeConnectionStatus()
        }

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStart() {
        super.onStart()
        mPresenter.onAttach(this)
    }

    override fun onStop() {
        mPresenter.disconnect()
        mPresenter.onDetach()
        showReconnect()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(btStateReceiver)
        colorPicker.unsubscribe(this)
        dialogManager.dismissAll()
        super.onDestroy()
    }

    override fun onColor(color: Int, fromUser: Boolean, shouldPropagate: Boolean) {
        if (allowChangeColor) {
            mPresenter.setColor(color)
            updateCurrentColor(color)
        }
    }

    private fun updateConnectionViewState(isConnected: Boolean) {
        if (isConnected) {
            colorPicker.alpha = 1f
            animationDropdown.isEnabled = true
            brightnessSlider.isEnabled = true
            allowChangeColor = true
            connectAction.text = getString(R.string.disconnect)
        } else {
            colorPicker.alpha = 0.5f
            animationDropdown.isEnabled = false
            brightnessSlider.isEnabled = false
            allowChangeColor = false
            connectAction.text = getString(R.string.connect)
        }
    }

    override fun updateCurrentColor(receivedColor: Int) {
        dropdownItem?.textColor = receivedColor
        brightnessSlider.setPrimaryColor(receivedColor)
        connectAction.setTextColor(receivedColor)
    }

    // TODO Change name to updateCurrentBrightness
    override fun updateColorBrightnessValue(receivedBrightness: Int) {
        brightnessSlider.setValue(receivedBrightness.toFloat(), true)
    }

    private fun changeConnectionStatus() {
        if (!mPresenter.isConnected()) {
            mPresenter.connect()
        } else {
            mPresenter.disconnect()
        }
    }

    // TODO Add updateCurrentAnimation

    override fun showMessage(text: String) {
        toast(text)
    }

    override fun showLoading(shouldShow: Boolean) {
        if (shouldShow)
            dialogManager.loading.show()
        else
            dialogManager.loading.dismiss()
    }

    override fun showDevicesList(devices: Array<String>, selectedDevice: (DialogInterface, String) -> Unit) {
        dialogManager.deviceSelection(devices, selectedDevice)
                .show()
    }

    override fun showPairWithDevice() {
        with(dialogManager.pairWithDevice) {
            positiveActionClickListener { dismiss() }
            show()
        }
    }

    override fun showConnected(device: String) {
        updateConnectionViewState(isConnected = true)

        showBottomMessage(R.string.connected_with, device)
        previousConnectionState = Connected(device)
    }

    override fun showDisconnected() {
        updateConnectionViewState(isConnected = false)

        if (previousConnectionState is Connected)
            showBottomMessage(R.string.disconnected)

        previousConnectionState = Disconnected
    }

    override fun showError(@StringRes messageId: Int, vararg args: Any) {
        showBottomMessage(messageId, args)
    }

    override fun showBtEnabled() {
        dialogManager.enableBT.dismiss()
    }

    override fun showBtDisabled() {
        with(dialogManager.enableBT) {
            positiveActionClickListener { runBtEnabler() }
            negativeActionClickListener { dismiss() }
            show()
        }
    }

    override fun showBtNotSupported() {
        dialogManager.btNotSupported
                .positiveActionClickListener { finish() }
                .show()
    }

    private fun showReconnect() {
        with(dialogManager.reconnect) {
            positiveActionClickListener {
                if (mPresenter.isBtEnabled()) mPresenter.connect()
                dismiss()
            }
            show()
        }
    }

    private fun showBottomMessage(@StringRes messageId: Int, vararg args: Any) {
        Snackbar.make(container, getString(messageId, args), Snackbar.LENGTH_SHORT).show()
    }

    private fun runBtEnabler() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
    }

    private fun initColorPicker() {
        colorPicker.subscribe(this)
        colorPicker.setInitialColor(Color.GRAY)
        colorPicker.reset()
    }

    private fun initAnimSpinner() {
        val animAdapter = ArrayAdapter<String>(this, R.layout.row_spn, resources.getStringArray(R.array.animations))
        animAdapter.setDropDownViewResource(R.layout.row_spn_dropdown)
        animationDropdown.adapter = animAdapter
        animationDropdown.setOnItemClickListener { parent, _, position, _ ->
            mPresenter.setAnimation(parent?.adapter?.getItem(position).toString())
            true
        }
    }
}
