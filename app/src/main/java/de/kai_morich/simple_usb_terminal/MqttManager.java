package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttManager {
    private static MqttManager instance;
    private MqttClient client;
    private MqttConnectOptions connectOptions;
    private String serverUri;

    private MqttManager() {}

    public static synchronized MqttManager getInstance() {
        if (instance == null) {
            instance = new MqttManager();
        }
        return instance;
    }

    public void connect(String serverUri, MqttConnectOptions options) throws MqttException {
        if (client == null) {
            this.serverUri = serverUri;
            this.connectOptions = options;
            String clientId = MqttClient.generateClientId();
            client = new MqttClient(serverUri, clientId, new MemoryPersistence());
        }
        if (!client.isConnected()) {
            client.connect(connectOptions);
        }
    }

    public MqttClient getClient() {
        return client;
    }

    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                client.close();
            } catch (MqttException e) {
                e.printStackTrace();
            }
            client = null;
        }
    }
}