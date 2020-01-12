package com.czterysery.ledcontroller.presenter

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.provider.Settings.Global.getString
import android.util.Log
import com.czterysery.ledcontroller.BluetoothStateBroadcastReceiver
import com.czterysery.ledcontroller.Messages
import com.czterysery.ledcontroller.R
import com.czterysery.ledcontroller.data.bluetooth.BluetoothController
import com.czterysery.ledcontroller.data.model.*
import com.czterysery.ledcontroller.data.socket.SocketManager
import com.czterysery.ledcontroller.view.MainView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MainPresenterImpl(
        private val bluetoothStateBroadcastReceiver: BluetoothStateBroadcastReceiver,
        private val btController: BluetoothController,
        private val socketManager: SocketManager
) : MainPresenter {
    private val TAG = "MainPresenter"

    private val bluetoothStateListener: (state: BluetoothState) -> Unit =
            { state: BluetoothState ->
                onBtStateChanged(state)
            }

    private val connectionStateListener: (state: ConnectionState) -> Unit =
            { state ->
                onConnectionStateChanged(state)
            }

    private var btStateDisposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null
    private var messagePublisherDisposable: Disposable? = null

    // TODO Remove
    private var colorChangeCounter = 3
    private var view: MainView? = null

    override fun onAttach(view: MainView) {
        this.view = view
        registerListeners()
    }

    override fun onDetach() {
        this.view = null
        disposeAll()
    }

    override fun connect() {
        if (isBtEnabled()) {
            showDevicesAndTryConnect()
        } else {
            view?.showBtDisabled()
        }
    }

    override fun disconnect() {
        sendConnectionMessage(connected = false)
        socketManager.disconnect()
                .subscribe()
    }

    // TODO Refactor
    override fun setColor(color: Int) {
        if (colorChangeCounter == 3) { //This blocks against multiple invocations with the same color
            val hexColor = String.format("#%06X", (0xFFFFFF and color))
            socketManager.writeMessage(Messages.SET_COLOR + hexColor + "\r\n")
            //colorChangeCounter = 0
        }
    }

    override fun setBrightness(value: Int) {
        socketManager.writeMessage(Messages.SET_BRIGHTNESS + value + "\r\n")
    }

    // TODO Refactor
    override fun setAnimation(anim: String) {
        for (i in 0..5) {
            Handler().postDelayed({
                socketManager.writeMessage(Messages.SET_ANIMATION + anim.toUpperCase() + "\r\n")
                //Log.d(TAG, "From reading message = ${socketManager.readMessage()}")
            }, 100)
        }
    }

    /* Get the current params from an ESP32 */
    // TODO Implement this and rename
    private fun loadCurrentParams() {
//        getColor()
//        getBrightness()
    }

    override fun isConnected() = socketManager.connectionState.value is Connected

    override fun isBtEnabled(): Boolean = btController.isEnabled

    private fun subscribeConnectionStateListener(listener: (state: ConnectionState) -> Unit) {
        connectionStateDisposable?.dispose()
        connectionStateDisposable = socketManager.connectionState
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { state -> listener(state) },
                        { error -> Log.e(TAG, "Error during observing connection state: $error") }
                )
    }

    private fun subscribeBluetoothStateListener(listener: (state: BluetoothState) -> Unit) {
        btStateDisposable?.dispose()
        btStateDisposable = bluetoothStateBroadcastReceiver.btState
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { state -> checkIfBtSupportedAndReturnState(listener, state) },
                        { error -> Log.e(TAG, "Error during observing BT state: $error") }
                )
    }

    private fun subscribeMessagePublisher() {
        messagePublisherDisposable?.dispose()
        messagePublisherDisposable = socketManager.messagePublisher
                .subscribeOn(Schedulers.io())
                .subscribe(
                        { message -> parseMessage(message) },
                        { view?.showError(R.string.error_receiving_message) }
                )
    }

    private fun onConnectionStateChanged(state: ConnectionState) {
        when (state) {
            is Connected -> {
                view?.showConnected(state.device)
                subscribeMessagePublisher()
                tryToGetConfiguration()
            }
            Disconnected -> {
                view?.showDisconnected()
            }
            is Error -> view?.showError(state.messageId)
        }
        view?.showLoading(shouldShow = false)
    }

    private fun onBtStateChanged(state: BluetoothState) {
        when (state) {
            Enabled -> view?.showBtEnabled()
            Disabled -> {
                socketManager.connectionState.onNext(Disconnected)
                view?.showBtDisabled()
            }
            NotSupported -> view?.showBtNotSupported()
            None -> // When app starts, BT is in previous state. Check state manually.
                if (isBtEnabled()) view?.showBtEnabled() else view?.showBtDisabled()
        }
    }

    private fun sendConnectionMessage(connected: Boolean) {
        if (connected)
            socketManager.writeMessage(Messages.CONNECTED + "\r\n")
        else
            socketManager.writeMessage(Messages.DISCONNECTED + "\r\n")
    }

    private fun checkIfBtSupportedAndReturnState(listener: (state: BluetoothState) -> Unit, state: BluetoothState) {
        if (btController.isSupported.not())
            listener(NotSupported)
        else
            listener(state)
    }

    private fun showDevicesAndTryConnect() {
        view?.let {
            val devices = btController.getDevices().keys.toTypedArray()
            if (devices.isNotEmpty()) {
                it.showLoading()
                it.showDevicesList(devices) { dialog, deviceName ->
                    // On selected device
                    dialog.dismiss()
                    tryToConnectWithDevice(deviceName)
                }
            } else {
                it.showPairWithDevice()
            }
        }
    }

    private fun tryToConnectWithDevice(deviceName: String) {
        when {
            btController.adapter == null ->
                socketManager.connectionState.onNext(Error(R.string.error_bt_not_available))

            btController.getDeviceAddress(deviceName) == null ->
                socketManager.connectionState.onNext(Error(R.string.error_cannot_find_device))

            else ->
                socketManager.connect(
                        btController.getDeviceAddress(deviceName) as String,
                        btController.adapter as BluetoothAdapter
                ).subscribeOn(Schedulers.io()).subscribe(
                        { sendConnectionMessage(connected = true) },
                        { view?.showLoading(shouldShow = false) }
                )
        }
    }

    private fun tryToGetConfiguration() {
        // TODO `showLoading`
        // TODO Here create get configuartion with 5sec or less timeout
        // TODO `hideLoading`
    }

    private fun parseMessage(message: String) {
        // TODO Here do sth useful with received message
    }

    private fun registerListeners() {
        subscribeBluetoothStateListener(bluetoothStateListener)
        subscribeConnectionStateListener(connectionStateListener)
    }

    private fun disposeAll() {
        messagePublisherDisposable?.dispose()
        btStateDisposable?.dispose()
        connectionStateDisposable?.dispose()
    }
}