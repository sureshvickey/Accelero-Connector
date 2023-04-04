package bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;


public class CCDTBluetoothManager {
    private static final String TAG = "CCDTBluetoothManager";
    private static CCDTBluetoothManager mCCDTBluetoothManager = null;
    // Listener for Bluetooth Status & Connection
    private BluetoothStateListener mBluetoothStateListener = null;
    private BluetoothConnectionListener mBluetoothConnectionListener = null;
    private AutoConnectionListener mAutoConnectionListener = null;
    // Context from activity which call this class
    private Context mContext;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter;
    // Member object for the bluetooth services
    private CCDTBluetoothService bluetoothService = null;
    // Name and Address of the connected device
    private String mDeviceName = null;
    private String mDeviceAddress = null;
    private boolean isConnected = false;
    private boolean isConnecting = false;
    private boolean isAutoConnecting = false;
    private boolean isAutoConnectionEnabled = false;
    private boolean isServiceRunning = false;
    private String keyword = "";
    private int c = 0;

    @SuppressLint("HandlerLeak")
    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BluetoothState.MESSAGE_WRITE:
                   // FileWrite.Log(Arrays.toString((byte[]) msg.obj),"Log File","Bluetooth","MESSAGE_WRITE", CCDTDiagApp.getAppContext());
                    break;
                case BluetoothState.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    //FileWrite.Log(Arrays.toString(readBuf),"Log File","Bluetooth","MESSAGE_READ", CCDTDiagApp.getAppContext());
                    if (readBuf != null && readBuf.length > 0) {
                      Log.i("Accelero read : ", Arrays.toString(readBuf));
                    }
                    break;
                case BluetoothState.MESSAGE_DEVICE_NAME:
                    mDeviceName = msg.getData().getString(BluetoothState.DEVICE_NAME);
                    mDeviceAddress = msg.getData().getString(BluetoothState.DEVICE_ADDRESS);
                    //FileWrite.Log("device name:"+mDeviceName+" device address:"+mDeviceAddress,"Log File","Bluetooth","MESSAGE_DEVICE_NAME", CCDTDiagApp.getAppContext());
                    if (mBluetoothConnectionListener != null)
                        mBluetoothConnectionListener.onDeviceConnected(mDeviceName, mDeviceAddress);
                    isConnected = true;
                    break;
                case BluetoothState.MESSAGE_STATE_CHANGE:
                    if (mBluetoothStateListener != null)
                        mBluetoothStateListener.onServiceStateChanged(msg.arg1);
                    //FileWrite.Log("state changed:"+msg.arg1,"Log File","Bluetooth","MESSAGE_STATE_CHANGE", CCDTDiagApp.getAppContext());
                    if (isConnected && msg.arg1 != BluetoothState.STATE_CONNECTED) {
                        isConnected = false;
                        mDeviceName = null;
                        mDeviceAddress = null;
                        removePairedDevices();
                        mBluetoothAdapter.cancelDiscovery();
                        if (mBluetoothConnectionListener != null){
                            mBluetoothConnectionListener.onDeviceDisconnected();
                        }
                    }

                    if (!isConnecting && msg.arg1 == BluetoothState.STATE_CONNECTING) {
                        isConnecting = true;
                    } else if (isConnecting) {
                        if (msg.arg1 != BluetoothState.STATE_CONNECTED) {
                            if (mBluetoothConnectionListener != null)
                                mBluetoothConnectionListener.onDeviceConnectionFailed();
                        }
                        isConnecting = false;
                    }
                    break;
            }
        }
    };

    private CCDTBluetoothManager(Context context) {
        mContext = context;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public static CCDTBluetoothManager getCCDTBluetoothManager(Context ctx) {
        if (mCCDTBluetoothManager == null) {
            mCCDTBluetoothManager = new CCDTBluetoothManager(ctx);
        }
        Log.i(TAG, mCCDTBluetoothManager.toString());
        return mCCDTBluetoothManager;
    }

    public boolean isBluetoothAvailable() {
            if (mBluetoothAdapter == null)
                return false;
            else
                return true;
    }

    public boolean isBluetoothEnabled() {
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();
    }

    public boolean isAutoConnecting() {
        return isAutoConnecting;
    }

    public BluetoothAdapter getBluetoothAdapter() {
        return mBluetoothAdapter;
    }

    public int getServiceState() {
        if(bluetoothService != null)
            return bluetoothService.getState();
        else
            return -1;
    }

    public boolean isServiceAvailable() {
        return bluetoothService != null;
    }

    public void setupService() {
        bluetoothService = new CCDTBluetoothService(mContext, mHandler);
    }

    public void startService() {
        if (bluetoothService != null) {
            if (bluetoothService.getState() == BluetoothState.STATE_NONE) {
                bluetoothService.start();
                isServiceRunning = true;
            }
        }
    }

    public void connect(Intent data) {
        String address = data.getExtras().getString(BluetoothState.EXTRA_DEVICE_ADDRESS);
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        bluetoothService.connect(device);
    }

    public void connect(String address) {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        bluetoothService.connect(device);
    }

    public void removePairedDevices() {
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice bt : pairedDevices) {
            try {
                String sConnectedDeviceName = bt.getName();
                if (sConnectedDeviceName != null ) { //&& sConnectedDeviceName.contains("CCDT")
                    Method m = bt.getClass().getMethod("removeBond", (Class[]) null);
                    m.invoke(bt, (Object[]) null);
                }
            } catch (NoSuchMethodException | IllegalAccessException
                    | InvocationTargetException e) {
                Log.e("Error in Remove Device", e.toString());
            }
        }
    }

    /*public void autoConnect(String keywordName) {
        if(!isAutoConnectionEnabled) {
            keyword = keywordName;
            isAutoConnectionEnabled = true;
            isAutoConnecting = true;
            if(mAutoConnectionListener != null)
                mAutoConnectionListener.onAutoConnectionStarted();
            final ArrayList<String> arr_filter_address = new ArrayList<String>();
            final ArrayList<String> arr_filter_name = new ArrayList<String>();
            String[] arr_name = getPairedDeviceName();
            String[] arr_address = getPairedDeviceAddress();
            for(int i = 0 ; i < arr_name.length ; i++) {
                if(arr_name[i].contains(keywordName)) {
                    arr_filter_address.add(arr_address[i]);
                    arr_filter_name.add(arr_name[i]);
                }
            }

            bcl = new BluetoothConnectionListener() {
                public void onDeviceConnected(String name, String address) {
                    bcl = null;
                    isAutoConnecting = false;
                }

                public void onDeviceDisconnected() { }
                public void onDeviceConnectionFailed() {
                    Log.e("CHeck", "Failed");
                    if(isServiceRunning) {
                        if(isAutoConnectionEnabled) {
                            c++;
                            if(c >= arr_filter_address.size())
                                c = 0;
                            connect(arr_filter_address.get(c));
                            Log.e("CHeck", "Connect");
                            if(mAutoConnectionListener != null)
                                mAutoConnectionListener.onNewConnection(arr_filter_name.get(c)
                                        , arr_filter_address.get(c));
                        } else {
                            bcl = null;
                            isAutoConnecting = false;
                        }
                    }
                }
            };

            setBluetoothConnectionListener(bcl);
            c = 0;
            if(mAutoConnectionListener != null)
                mAutoConnectionListener.onNewConnection(arr_name[c], arr_address[c]);
            if(arr_filter_address.size() > 0)
                connect(arr_filter_address.get(c));
            else
                Toast.makeText(mContext, "Device name mismatch", Toast.LENGTH_SHORT).show();
        }
    }*/

    public void disconnect() {
        if(bluetoothService != null) {
            bluetoothService.stop();
            bluetoothService = null;
            mCCDTBluetoothManager = null;
        }
    }

    public void setBluetoothConnectionListener(BluetoothConnectionListener listener) {
        mBluetoothConnectionListener = listener;
    }

    public void setBluetoothStateListener(BluetoothStateListener listener) {
        mBluetoothStateListener = listener;
    }

    public void setAutoConnectionListener(AutoConnectionListener listener) {
        mAutoConnectionListener = listener;
    }

    public void enable() {
        if (mBluetoothAdapter != null)
            mBluetoothAdapter.enable();
    }

    public void disable() {
        if (mBluetoothAdapter != null)
            mBluetoothAdapter.disable();
    }

    public void send(byte[] reqBytes) {
        if (bluetoothService != null && bluetoothService.getState() == BluetoothState.STATE_CONNECTED)
            bluetoothService.write(reqBytes);
    }

    public String getConnectedDeviceName() {
        return mDeviceName;
    }

    public interface BluetoothStateListener {
        public void onServiceStateChanged(int state);
    }

    public interface BluetoothConnectionListener {
        public void onDeviceConnected(String name, String address);

        public void onDeviceDisconnected();

        public void onDeviceConnectionFailed();
    }

    public interface AutoConnectionListener {
        public void onAutoConnectionStarted();
        public void onNewConnection(String name, String address);
    }

}
