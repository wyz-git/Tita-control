package de.kai_morich.simple_usb_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch; 
import android.content.Intent;
import de.kai_morich.simple_usb_terminal.FullscreenHelloActivity;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;


import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class DevicesFragment extends ListFragment {

    static class ListItem {
        UsbDevice device;
        int port;
        UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    private final ArrayList<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 420000;

    private MqttClient client;
    private Handler mqttHandler = new Handler(Looper.getMainLooper());
    private Runnable mqttRunnable;
    private static final long PUBLISH_INTERVAL = 500; // 500ms
    private static final String MQTT_SERVER = "tcp://119.23.220.15:1883";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        listAdapter = new ArrayAdapter<ListItem>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                ListItem item = listItems.get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                if (item.driver == null)
                    text1.setText("<no driver>");
                else if (item.driver.getPorts().size() == 1)
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", ""));
                else
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", "") + ", Port " + item.port);
                text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X", item.device.getVendorId(), item.device.getProductId()));
                return view;
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);

        // 如果没有USB设备连接，显示开关控件
        View switchContainer = getActivity().getLayoutInflater().inflate(R.layout.switch_container, null, false);
        getListView().addHeaderView(switchContainer, null, false);

        setListAdapter(listAdapter);

        // 初始化 MQTT 客户端并启动定时任务
        connectToMqttServer();
        startMqttPublishTask();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopMqttPublishTask(); // 停止定时任务
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        connectToMqttServer();
        startMqttPublishTask();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.refresh) {
            refresh();
            return true;
        } else if (id == R.id.baud_rate) {
            final String[] baudRates = getResources().getStringArray(R.array.baud_rates);
            int pos = java.util.Arrays.asList(baudRates).indexOf(String.valueOf(baudRate));
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Baud rate");
            builder.setSingleChoiceItems(baudRates, pos, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int item) {
                    baudRate = Integer.parseInt(baudRates[item]);
                    dialog.dismiss();
                }
            });
            builder.create().show();
            return true;
        } else if (id == R.id.joystick) {  // 新增摇杆菜单处理
            // 启动全屏Activity
            Intent intent = new Intent(getActivity(), FullscreenHelloActivity.class);
            startActivity(intent);
            return true;
        }else {
            return super.onOptionsItemSelected(item);
        }
    }

    void refresh() {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if (driver != null) {
                for (int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new ListItem(device, port, driver));
            } else {
                listItems.add(new ListItem(device, 0, null));
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        ListItem item = listItems.get(position - 1);
        if (item.driver == null) {
            Toast.makeText(getActivity(), "no driver", Toast.LENGTH_SHORT).show();
        } else {
            Bundle args = new Bundle();
            args.putInt("device", item.device.getDeviceId());
            args.putInt("port", item.port);
            args.putInt("baud", baudRate);
            Fragment fragment = new TerminalFragment();
            fragment.setArguments(args);
            getParentFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit();
        }
    }

    private void connectToMqttServer() {
        try {
            client = new MqttClient(MQTT_SERVER, "DevicesFragmentClient", new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            client.connect(connOpts);
        } catch (MqttException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), "MQTT connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startMqttPublishTask() {
        mqttRunnable = new Runnable() {
            @Override
            public void run() {
                publishSwitchStates();
                mqttHandler.postDelayed(this, PUBLISH_INTERVAL); // 每隔 500ms 执行一次
            }
        };
        mqttHandler.post(mqttRunnable); // 启动任务
    }

    private void stopMqttPublishTask() {
        if (mqttRunnable != null) {
            mqttHandler.removeCallbacks(mqttRunnable); // 停止任务
        }
    }

    private String getLoginAccount() {
        SharedPreferences sharedPreferences = getActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        return sharedPreferences.getString("login_account", "default_account");
    }

    private String getMqttTopic() {
        String loginAccount = getLoginAccount();
        return loginAccount + "-control";
    }

    private void publishSwitchStates() {
        if (client == null || !client.isConnected()) {
            return; // 如果 MQTT 客户端未连接，则不发布
        }

        // 获取 MQTT Topic
        String mqttTopic = getMqttTopic();

        // 获取开关控件
        View switchContainer = getListView().getChildAt(0); // 获取开关容器
        if (switchContainer == null) {
            return;
        }

        // 构建 JSON 消息
        JSONObject json = new JSONObject();
        try {
            Switch switch1 = (Switch) switchContainer.findViewById(R.id.switch1);
            Switch switch2 = (Switch) switchContainer.findViewById(R.id.switch2);
            Switch switch3 = (Switch) switchContainer.findViewById(R.id.switch3);
            Switch switch4 = (Switch) switchContainer.findViewById(R.id.switch4);
            Switch switch5 = (Switch) switchContainer.findViewById(R.id.switch5);
            Switch switch6 = (Switch) switchContainer.findViewById(R.id.switch6);
            Switch switch7 = (Switch) switchContainer.findViewById(R.id.switch7);
            Switch switch8 = (Switch) switchContainer.findViewById(R.id.switch8);
            Switch switch9 = (Switch) switchContainer.findViewById(R.id.switch9);
            Switch switch10 = (Switch) switchContainer.findViewById(R.id.switch10);

            json.put("1", switch1.isChecked());
            json.put("2", switch2.isChecked());
            json.put("3", switch3.isChecked());
            json.put("4", switch4.isChecked());
            json.put("5", switch5.isChecked());
            json.put("6", switch6.isChecked());
            json.put("7", switch7.isChecked());
            json.put("8", switch8.isChecked());
            json.put("9", switch9.isChecked());
            json.put("10", switch10.isChecked());
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        // 发布到 MQTT
        try {
            MqttMessage message = new MqttMessage(json.toString().getBytes());
            message.setQos(0); // 设置 QoS 为 0
            client.publish(mqttTopic, message);
        } catch (MqttException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), "MQTT publish failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}