package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.XonXoffFilter;
import org.eclipse.paho.client.mqttv3.MqttClient;  
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;  
import org.eclipse.paho.client.mqttv3.MqttException;  
import org.eclipse.paho.client.mqttv3.MqttMessage;  
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import android.net.Uri;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private final Handler mainLooper;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ImageButton sendBtn;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private enum SendButtonState {Idle, Busy, Disabled};

    private ControlLines controlLines = new ControlLines();
    private XonXoffFilter flowControlFilter;

    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;
    private List<Byte> serialBuffer = new ArrayList<>();
    private static final String MQTT_SERVER = "tcp://119.23.220.15:1883";  
    private static final int MQTT_PORT = 1883;  
    private static String MQTT_TOPIC = "tita3037207/joy10";  
    private MqttClient client;  

    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    private void connectToMqttServer() {  
        client = MqttManager.getInstance().getClient();
    }  

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // MQTT_TOPIC = getMqttTopic();
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");  
    }

    private String getMqttTopic() {
        String loginAccount = getLoginAccount();
        return loginAccount + "/control";
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();

        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        connectToMqttServer();
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines.onCreateView(view);
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        controlLines.onPrepareOptionsMenu(menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, which) -> {
                newline = newlineValues[which];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            item.setChecked(controlLines.showControlLines(!item.isChecked()));
            return true;
        } else if (id == R.id.flowControl) {
            controlLines.selectFlowControl();
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        updateSendBtn(SendButtonState.Idle);
        usbSerialPort = null;
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg;
        byte[] data;
        if(hexEnabled) {
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, TextUtil.fromHexString(str));
            TextUtil.toHexString(sb, newline.getBytes());
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
        } else {
            msg = str;
            data = (str + newline).getBytes();
        }
        try {
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (SerialTimeoutException e) { // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private String loginAccount;

    public void onLoginSuccess(String username) {
        this.loginAccount = username; // 接收登录账号
    }

    private String getLoginAccount() {
        return loginAccount != null ? loginAccount : "default_account";
    }


    private void sendAgain(byte[] data0, int offset) {
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Busy : SendButtonState.Disabled);
        if (connected != Connected.True) {
            return;
        }
        byte[] data;
        if (offset == 0) {
            data = data0;
        } else {
            data = new byte[data0.length - offset];
            System.arraycopy(data0, offset, data, 0, data.length);
        }
        try {
            service.write(data);
        } catch (SerialTimeoutException e) {
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
            return;
        } catch (Exception e) {
            onSerialIoError(e);
        }
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
    }

    public void publishMQTTMessage(byte[] message) throws MqttException {  
        MqttMessage mqttMessage = new MqttMessage(message);  
        mqttMessage.setQos(0); // 设置消息的服务质量  
        client.publish(MQTT_TOPIC, mqttMessage);  
        System.out.println("Message published");  
    }

    public void mqtt_receive(ArrayDeque<byte[]> datas) {  
            while (!datas.isEmpty()) {  
                byte[] newData = datas.poll(); // 取出队列中的一个数据块  
                for (byte b : newData) {  
                    serialBuffer.add((byte) b); // 添加到缓冲区  
                }  
    
                // 查找并处理所有以0xc8开头的消息  
                int index = 0;  
                while (index < serialBuffer.size()) {  
                    if (serialBuffer.get(index) == (byte) 0xc8 && serialBuffer.size() - index >= 26) {  
                        // 提取消息  
                        byte[] messageToSend = new byte[26];  
                        for (int i = 0; i < 26; i++) {  
                            messageToSend[i] = serialBuffer.get(index + i);  
                        }  
    
                        // 假设你有一个函数来发布MQTT消息  
                                        // 发布MQTT消息  
                        try {  
                            publishMQTTMessage(messageToSend);;  
                        } catch (MqttException e) {  
                            status("loss connection");
                        } 
                        // 移除已处理的消息  
                        // serialBuffer = serialBuffer.subList(index + 26, serialBuffer.size()); 
                        serialBuffer.subList(index, index + 26).clear(); 
                        // index = 0; // 重置索引  
                    } else {  
                        index++;  
                    }  
                }  
            }  
    }  

    private void receive(ArrayDeque<byte[]> datas) {
        mqtt_receive(datas);
        // return;
        // SpannableStringBuilder spn = new SpannableStringBuilder();
        // for (byte[] data : datas) {
        //     if (flowControlFilter != null)
        //         data = flowControlFilter.filter(data);
        //     if (hexEnabled) {
        //         spn.append(TextUtil.toHexString(data)).append('\n');
        //     } else {
        //         String msg = new String(data);
        //         if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
        //             // don't show CR as ^M if directly before LF
        //             msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
        //             // special handling if CR and LF come in separate fragments
        //             if (pendingNewline && msg.charAt(0) == '\n') {
        //                 if(spn.length() >= 2) {
        //                     spn.delete(spn.length() - 2, spn.length());
        //                 } else {
        //                     Editable edt = receiveText.getEditableText();
        //                     if (edt != null && edt.length() >= 2)
        //                         edt.delete(edt.length() - 2, edt.length());
        //                 }
        //             }
        //             pendingNewline = msg.charAt(msg.length() - 1) == '\r';
        //         }
        //         spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
        //     }
        // } 
        // receiveText.append(spn);
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    void updateSendBtn(SendButtonState state) {
        sendBtn.setEnabled(state == SendButtonState.Idle);
        sendBtn.setImageAlpha(state == SendButtonState.Idle ? 255 : 64);
        sendBtn.setImageResource(state == SendButtonState.Disabled ? R.drawable.ic_block_white_24dp : R.drawable.ic_send_white_24dp);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }
    private void requestBatteryOptimizationSettings() {  
        Intent intent = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");  
        Uri uri = Uri.parse("package:" + getActivity().getPackageName());  
        intent.setData(uri);  
        startActivity(intent);  
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
        {
            showNotificationSettings();
            requestBatteryOptimizationSettings();
        }
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;

        private View frame;
        private ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        private boolean showControlLines;                                               // show & update control line buttons
        private UsbSerialPort.FlowControl flowControl = UsbSerialPort.FlowControl.NONE; // !NONE: update send button state

        boolean sendAllowed = true;

        ControlLines() {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        void onCreateView(View view) {
            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        void onPrepareOptionsMenu(Menu menu) {
            try {
                EnumSet<UsbSerialPort.ControlLine> scl = usbSerialPort.getSupportedControlLines();
                EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
                menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty());
                menu.findItem(R.id.controlLines).setChecked(showControlLines);
                menu.findItem(R.id.flowControl).setEnabled(sfc.size() > 1);
            } catch (Exception ignored) {
            }
        }

        void selectFlowControl() {
            EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
            UsbSerialPort.FlowControl fc = usbSerialPort.getFlowControl();
            ArrayList<String> names = new ArrayList<>();
            ArrayList<UsbSerialPort.FlowControl> values = new ArrayList<>();
            int pos = 0;
            names.add("<none>");
            values.add(UsbSerialPort.FlowControl.NONE);
            if (sfc.contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                names.add("RTS/CTS control lines");
                values.add(UsbSerialPort.FlowControl.RTS_CTS);
                if (fc == UsbSerialPort.FlowControl.RTS_CTS) pos = names.size() -1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.DTR_DSR)) {
                names.add("DTR/DSR control lines");
                values.add(UsbSerialPort.FlowControl.DTR_DSR);
                if (fc == UsbSerialPort.FlowControl.DTR_DSR) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF_INLINE)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF_INLINE);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF_INLINE) pos = names.size() - 1;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Flow Control");
            builder.setSingleChoiceItems(names.toArray(new CharSequence[0]), pos, (dialog, which) -> {
                dialog.dismiss();
                try {
                    flowControl = values.get(which);
                    usbSerialPort.setFlowControl(flowControl);
                    flowControlFilter = usbSerialPort.getFlowControl() == UsbSerialPort.FlowControl.XON_XOFF_INLINE ? new XonXoffFilter() : null;
                    start();
                } catch (Exception e) {
                    status("Set flow control failed: "+e.getClass().getName()+" "+e.getMessage());
                    flowControl = UsbSerialPort.FlowControl.NONE;
                    flowControlFilter = null;
                    start();
                }
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
            builder.setNeutralButton("Info", (dialog, which) -> {
                dialog.dismiss();
                AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                builder2.setTitle("Flow Control").setMessage("If send is stopped by the external device, the 'Send' button changes to 'Blocked' icon.");
                builder2.create().show();
            });
            builder.create().show();
        }

        public boolean showControlLines(boolean show) {
            showControlLines = show;
            start();
            return showControlLines;
        }

        void start() {
            if (showControlLines) {
                try {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getSupportedControlLines();
                    rtsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RTS) ? View.VISIBLE : View.INVISIBLE);
                    ctsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CTS) ? View.VISIBLE : View.INVISIBLE);
                    dtrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DTR) ? View.VISIBLE : View.INVISIBLE);
                    dsrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DSR) ? View.VISIBLE : View.INVISIBLE);
                    cdBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CD)   ? View.VISIBLE : View.INVISIBLE);
                    riBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RI)   ? View.VISIBLE : View.INVISIBLE);
                } catch (IOException e) {
                    showControlLines = false;
                    status("getSupportedControlLines() failed: " + e.getMessage());
                }
            }
            frame.setVisibility(showControlLines ? View.VISIBLE : View.GONE);
            if(flowControl == UsbSerialPort.FlowControl.NONE) {
                sendAllowed = true;
                updateSendBtn(SendButtonState.Idle);
            }

            mainLooper.removeCallbacks(runnable);
            if (showControlLines || flowControl != UsbSerialPort.FlowControl.NONE) {
                run();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            sendAllowed = true;
            updateSendBtn(SendButtonState.Idle);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                if (showControlLines) {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                    if(rtsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setChecked(!rtsBtn.isChecked());
                    if(ctsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setChecked(!ctsBtn.isChecked());
                    if(dtrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setChecked(!dtrBtn.isChecked());
                    if(dsrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setChecked(!dsrBtn.isChecked());
                    if(cdBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.CD))  cdBtn.setChecked(!cdBtn.isChecked());
                    if(riBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.RI))  riBtn.setChecked(!riBtn.isChecked());
                }
                if (flowControl != UsbSerialPort.FlowControl.NONE) {
                    switch (usbSerialPort.getFlowControl()) {
                        case DTR_DSR:         sendAllowed = usbSerialPort.getDSR(); break;
                        case RTS_CTS:         sendAllowed = usbSerialPort.getCTS(); break;
                        case XON_XOFF:        sendAllowed = usbSerialPort.getXON(); break;
                        case XON_XOFF_INLINE: sendAllowed = flowControlFilter != null && flowControlFilter.getXON(); break;
                        default:              sendAllowed = true;
                    }
                    updateSendBtn(sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
                }
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

    }

}
