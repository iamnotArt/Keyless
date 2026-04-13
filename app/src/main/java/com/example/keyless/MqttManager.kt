package com.example.keyless

import android.content.Context
import android.util.Log
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MqttManager(context: Context) {

    private val serverURI = "tcp://broker.hivemq.com:1883"
    private val clientId = "KeylessApp_" + System.currentTimeMillis()

    private val client = MqttAndroidClient(context, serverURI, clientId)

    fun connect(onConnected: () -> Unit = {}) {
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
        }

        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.e("MQTT", "Connection lost", cause)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.d("MQTT", "Received: ${message.toString()}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d("MQTT", "Connected")
                subscribeToStatus()
                onConnected()
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e("MQTT", "Failed to connect", exception)
            }
        })
    }

    fun publish(message: String) {
        if (!client.isConnected) {
            Log.e("MQTT", "Client not connected")
            return
        }

        val topic = "esp32/lock/control"
        val mqttMessage = MqttMessage(message.toByteArray())
        mqttMessage.qos = 1
        mqttMessage.isRetained = false

        client.publish(topic, mqttMessage)
        Log.d("MQTT", "Published: $message")
    }

    private fun subscribeToStatus() {
        val topic = "esp32/lock/status"

        client.subscribe(topic, 1, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d("MQTT", "Subscribed to status")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e("MQTT", "Subscribe failed", exception)
            }
        })
    }
}