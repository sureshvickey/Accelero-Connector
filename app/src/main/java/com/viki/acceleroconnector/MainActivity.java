package com.viki.acceleroconnector;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import bluetooth.CCDTBluetoothManager;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public static final int MULTIPLE_PERMISSIONS = 10;
    private static final String TAG = "ConnectActivity";
    private BluetoothAdapter mBtAdapter;
    private ArrayAdapter<String> mPairedDevicesArrayAdapter;
    CCDTBluetoothManager bluetoothMgr;
    Button btnBluetooth, menuButton;
    TextView txtDisplay, txtScan;
    Button refresh;
    ListView pairedListView;
    Activity mContext;
    boolean isDueToPopUp = false;
    private final String MY_UUID = "00001101-0000-1000-8000-00805F9B34FB";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPermissions();
        bluetoothMgr = CCDTBluetoothManager.getCCDTBluetoothManager(this);
        mContext = this;
        if (!bluetoothMgr.isBluetoothAvailable()) {
            Toast.makeText(getApplicationContext()
                    , "Bluetooth is not available"
                    , Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissionsList, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissionsList, grantResults);
        HashMap<String, Boolean> enabledPermission = new HashMap<>();
        if (requestCode == MULTIPLE_PERMISSIONS) {
            if (grantResults.length > 0) {
                String permissionsDenied = "";
                if (permissionsList != null && permissionsList.length > 0) {
                    for (String per : permissionsList) {
                        if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                            permissionsDenied += "\n" + per;
                            Log.e("permissions-->", permissionsDenied);
                            enabledPermission.put(per, false);
                        } else {
                            enabledPermission.put(per, true);
                        }

                    }
                }
            }
        }
    }

    private boolean checkPermissions() {
        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        String[] permissions;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        } else {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE};
        }
        for (String p : permissions) {
            result = ContextCompat.checkSelfPermission(MainActivity.this, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), MULTIPLE_PERMISSIONS);
            return false;
        }

        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (bluetoothMgr == null) {
            bluetoothMgr = CCDTBluetoothManager.getCCDTBluetoothManager(this);
        }
        if (!bluetoothMgr.isBluetoothEnabled()) {
            bluetoothMgr.enable();
        }
        if (!bluetoothMgr.isServiceAvailable()) {
            bluetoothMgr.setupService();
            bluetoothMgr.startService();
        }
        loadUiComponents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerForBluetoothActions();
        if (mPairedDevicesArrayAdapter.getCount() > 0 && !pairedListView.isEnabled()) {
            pairedListView.setEnabled(true);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        isDueToPopUp = false;
        bluetoothMgr.disconnect();
        bluetoothMgr.removePairedDevices();
        mBtAdapter.cancelDiscovery();
        bluetoothMgr = null;
        try {
            moveTaskToBack(true);
            finishAffinity();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        try {
            if (!isDueToPopUp) {
                mBtAdapter.cancelDiscovery();
                unregisterReceiver(mReceiver);
            }
        } catch (Exception e) {

        }
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.detail_refresh_btn:
                if (bluetoothMgr != null) {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ||
                            Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                        if (!locationStatusCheck()) {
                            Toast.makeText(getApplicationContext(), "enable location", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                            break;
                        }
                    }
                    if (!bluetoothMgr.isBluetoothEnabled()) {
                        bluetoothMgr.enable();
                    }
                    bluetoothMgr.removePairedDevices();
                    doDiscovery();
                } else {
                    mPairedDevicesArrayAdapter.clear();
                    mPairedDevicesArrayAdapter.notifyDataSetChanged();
                }
                break;

        }
    }

    public boolean locationStatusCheck() {
        final LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return manager.isLocationEnabled();
        } else {
            return false;
        }
    }

    private void loadUiComponents() {
        btnBluetooth = findViewById(R.id.btnBluetooth);
        txtDisplay = findViewById(R.id.txtdisplay);
        txtScan = findViewById(R.id.btnlistviewhead);
        refresh = findViewById(R.id.detail_refresh_btn);
        refresh.setOnClickListener(this);
        refresh.setEnabled(true);
        refresh.setClickable(true);
        mPairedDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.device_name) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // Get the current item from ListView
                View view = super.getView(position, convertView, parent);
                if (position % 2 == 1) {
                    // Set a background color for ListView regular row/item
                    view.setBackgroundColor(getResources().getColor(R.color.lighterGray));
                } else {
                    // Set the background color for alternate row/item
                    view.setBackgroundColor(getResources().getColor(R.color.invertedGray));
                }
                return view;
            }
        };

        // Find and set up the ListView for paired devices
        pairedListView = findViewById(R.id.list);
        pairedListView.setAdapter(mPairedDevicesArrayAdapter);
        pairedListView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        pairedListView.setOnItemClickListener(mDeviceClickListener);
        pairedListView.setEnabled(true);
        // Get the local Bluetooth adapter
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        // Remove paired device
        bluetoothMgr.removePairedDevices();
        doDiscovery();
        bluetoothMgr.setBluetoothConnectionListener(new CCDTBluetoothManager.BluetoothConnectionListener() {
            @Override
            public void onDeviceConnected(String name, String address) {
                isDueToPopUp = false;
                Toast.makeText(getApplicationContext(), address + " " + bluetoothMgr.getConnectedDeviceName(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDeviceDisconnected() {
                Toast.makeText(getApplicationContext(), "Dongle_Session_Closed", Toast.LENGTH_SHORT).show();
                mPairedDevicesArrayAdapter.clear();
                mPairedDevicesArrayAdapter.notifyDataSetChanged();
                pairedListView.setEnabled(true);
                refresh.setClickable(true);
                refresh.setEnabled(true);
                Intent androidsolved_intent = new Intent(getApplicationContext(), MainActivity.class);

                startActivity(androidsolved_intent);
            }

            @Override
            public void onDeviceConnectionFailed() {
                Toast.makeText(getApplicationContext(), "BL_connect_failed", Toast.LENGTH_SHORT).show();
                mPairedDevicesArrayAdapter.clear();
                Set<BluetoothDevice> mPairedDevices = mBtAdapter.getBondedDevices();
                BluetoothDevice mDevice = null;
                if (mBtAdapter.isEnabled()) {
                    // put it's one to the adapter
                    for (BluetoothDevice device : mPairedDevices) {
                        mPairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                        mDevice = device;
                        break;
                    }

                    Toast.makeText(getApplicationContext(), "paired devices", Toast.LENGTH_SHORT).show();
                    try {
                        BluetoothSocket mSocket = mDevice.createRfcommSocketToServiceRecord(UUID.fromString(MY_UUID));
                        mSocket.connect();
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                txtDisplay.setText("refresh_again");
                //mPairedDevicesArrayAdapter.clear();
                mPairedDevicesArrayAdapter.notifyDataSetChanged();
                pairedListView.setEnabled(true);
                refresh.setClickable(true);
                refresh.setEnabled(true);
            }
        });
    }

    private void doDiscovery() {
        if (!mBtAdapter.isDiscovering()) {
            mBtAdapter.startDiscovery();
        }else{
            mBtAdapter.cancelDiscovery();
            mBtAdapter.startDiscovery();
        }
    }

    // The on-click listener for all devices in the ListViews
    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
            String info = ((TextView) v).getText().toString();
            if (!bluetoothMgr.isBluetoothEnabled()) {
//                AppUtility.showToastAtCenter(R.string.bluetooth_turn_on,"",
//                        Toast.LENGTH_SHORT, Gravity.CENTER);
            } else {
                if (info.equals("No devices found")) {
                    return;
                } else {
                    pairedListView.setEnabled(false);
                    // Cancel discovery because it's costly and we're about to connect
                    if (mBtAdapter.isDiscovering())
                        mBtAdapter.cancelDiscovery();
                    // Get the device MAC address, which is the last 17 chars in the View
                    connectCCDTDevice(info.substring(info.length() - 17));
                }
            }
        }
    };

    private void connectCCDTDevice(String deviceAddress) {
        isDueToPopUp = true;
        txtDisplay.setText("");
        final AlertDialog.Builder adb = new AlertDialog.Builder(MainActivity.this);
        adb.setTitle("confirm_pair");
        adb.setMessage("connect_device");
        adb.setNegativeButton("Cancel", (dialog, which) -> {
            int position = pairedListView.getCheckedItemPosition();
            pairedListView.setItemChecked(position, false);
            pairedListView.setEnabled(true);
        });
        adb.setPositiveButton("Ok", (dialog, which) -> {
            if(bluetoothMgr != null){
                bluetoothMgr.connect(deviceAddress);
            }else{
                mPairedDevicesArrayAdapter.clear();
                mPairedDevicesArrayAdapter.notifyDataSetChanged();
            }
        });
        AlertDialog alert = adb.create();
        alert.setCanceledOnTouchOutside(false);
        alert.setCancelable(false);
        alert.show();
    }

    public void registerForBluetoothActions() {
        try {
            // Register for bluetooth events
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            registerReceiver(mReceiver, filter);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            try {
                if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    Log.i("state", state + "");
                    switch (state) {
                        case BluetoothAdapter.STATE_OFF:
//                            AppUtility.showToastAtCenter(R.string.bluetooth_state_off,"",
//                                    Toast.LENGTH_SHORT, Gravity.CENTER);
                            //   FileWrite.Log("STATE_OFF", "Log File", "Bluetooth", "Adapter State", this);
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            //   FileWrite.Log("STATE_TURNING_OFF", "Log File", "Bluetooth", "Adapter State", this);
//                            AppUtility.showToastAtCenter(R.string.bluetooth_turning_off,"",
//                                    Toast.LENGTH_SHORT, Gravity.CENTER);
                            break;
                        case BluetoothAdapter.STATE_ON:
//                            AppUtility.showToastAtCenter(R.string.bluetooth_state_on,"",
//                                    Toast.LENGTH_SHORT, Gravity.CENTER);
                            //  FileWrite.Log("STATE_ON", "Log File", "Bluetooth", "Adapter State", this);
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            //  FileWrite.Log("STATE_TURNING_ON", "Log File", "Bluetooth", "Adapter State", this);
//                            AppUtility.showToastAtCenter(R.string.bluetooth_turning_on,"",
//                                    Toast.LENGTH_SHORT, Gravity.CENTER);
                            break;
                    }

                } /*else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    BluetoothDevice ccdtDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if ((ccdtDevice != null) && ccdtDevice.getName().contains("CCDT")) {
                      //  FileWrite.Log("ACTION_ACL_DISCONNECTED", "Log File", "Bluetooth", "Adapter State", this);
                        AppUtility.showToastAtCenter(R.string.BL_disconnected, "", AppConstant.TOAST_VERY_SHORT, Gravity.BOTTOM);
                    }
                }*/
                else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                    //  FileWrite.Log("ACTION_DISCOVERY_STARTED", "Log File", "Bluetooth", "Adapter State", this);
                    refresh.setClickable(false);
                    refresh.setEnabled(false);
                    mPairedDevicesArrayAdapter.clear();
                    mPairedDevicesArrayAdapter.notifyDataSetChanged();
                    txtDisplay.setText("Scanning Started");
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    //  FileWrite.Log("ACTION_DISCOVERY_FINISHED", "Log File", "Bluetooth", "Adapter State", this);
                    refresh.setClickable(true);
                    refresh.setEnabled(true);
                    //discovery finishes, dismiss progress dialog
                    txtDisplay.setText("scan_completed");
                    if (mPairedDevicesArrayAdapter.isEmpty()) {
                        String strNoFound = getIntent().getStringExtra("no_devices_found");
                        if (strNoFound == null)
                            strNoFound = "No devices found";
                        mPairedDevicesArrayAdapter.add(strNoFound);
                        mPairedDevicesArrayAdapter.notifyDataSetChanged();
                    }
                } else if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice ccdtdevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    //  FileWrite.Log("ACTION_FOUND" + ccdtdevice.getBondState(), "Log File", "Bluetooth", "Adapter State", this);
                    if (ccdtdevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                        txtDisplay.setText("scan_completed");
                        refresh.setClickable(false);
                        refresh.setEnabled(false);
                        String sDisplayText = ccdtdevice.getName();
                        if (sDisplayText != null ) { //&& sDisplayText.contains("CCDT")
                            try {
                                boolean isAdded = false;
                                String foundDevice = ccdtdevice.getName() + "\n" + ccdtdevice.getAddress();
                                for (int i = 0; i < mPairedDevicesArrayAdapter.getCount(); i++) {
                                    if (mPairedDevicesArrayAdapter.getItem(i).equals(foundDevice))
                                        isAdded = true;
                                }
                                if (!isAdded) {
                                    mPairedDevicesArrayAdapter.add(foundDevice);
                                    mPairedDevicesArrayAdapter.notifyDataSetChanged();
                                }
                            } catch (Exception ex) {
                                Log.e("CCDT :", "Exception -->" + ex);
                            }
                        }
                    }
                } else if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST)) {
                    isDueToPopUp = true;
                    try {
//                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
//                        int pin=intent.getIntExtra("android.bluetooth.device.extra.PAIRING_KEY", 0);
//                        //the pin in case you need to accept for an specific pin
//                        Log.d("PIN", " " + intent.getIntExtra("android.bluetooth.device.extra.PAIRING_KEY",0));
//                        //maybe you look for a name or address
//                        Log.d("Bonded", device.getName());
//                        byte[] pinBytes;
//                        pinBytes = (""+pin).getBytes("UTF-8");
//                        device.setPin(pinBytes);
//                        //setPairing confirmation if neeeded
//                        device.setPairingConfirmation(true);
                        final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);//
                       // device.setPin(AppConstant.PASSKEY.getBytes());
                        device.createBond();
                        abortBroadcast();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    Log.i(TAG, "After Pairing");
                }
            } catch (Exception exe) {
                Log.e("CCDT", " Error 11 -->" + exe);
            }
        }
    };





}