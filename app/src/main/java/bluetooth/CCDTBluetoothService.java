package bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class CCDTBluetoothService {
    private static final String TAG = "CCDTBluetoothService";
    private static final boolean DEBUG = false;

    private final BluetoothAdapter adapter;
    private final Handler activityHandler;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int mState;
    Context context;

    public CCDTBluetoothService(Context context, Handler handler) {
        adapter = BluetoothAdapter.getDefaultAdapter();
        mState = BluetoothState.STATE_NONE;
        activityHandler = handler;
        this.context = context;
    }

    private synchronized void setState(int state) {
        if (DEBUG)
            Log.d(TAG, "setState() " + state + " -> " + state);
        mState = state;

        // Send the new state to the Handler so the calling Activity can update its UI
        activityHandler.obtainMessage(BluetoothState.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    public synchronized int getState() {
        return mState;
    }

    public synchronized void start() {
        if (DEBUG)
            Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(BluetoothState.STATE_LISTEN);

    }

    public synchronized void connect(BluetoothDevice device) {
        if (DEBUG)
            Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == BluetoothState.STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread.cancel();
                connectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Start the thread to connect with the given device
        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(BluetoothState.STATE_CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket,
                                       BluetoothDevice device) {

        if (DEBUG)
            Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        // Send the paramName of the connected device back to the UI Activity
        Message msg = activityHandler
                .obtainMessage(BluetoothState.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothState.DEVICE_NAME, device.getName());
        bundle.putString(BluetoothState.DEVICE_ADDRESS, device.getAddress());
        msg.setData(bundle);
        activityHandler.sendMessage(msg);

        setState(BluetoothState.STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (DEBUG)
            Log.d(TAG, "stop");
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(BluetoothState.STATE_NONE);
    }

    public void write(byte[] out) {

        ConnectedThread r;

        synchronized (this) {
            if (mState != BluetoothState.STATE_CONNECTED)
                return;
            r = connectedThread;
        }

        r.write(out);
    }

    private void connectionFailed() {

        Message msg = activityHandler.obtainMessage(BluetoothState.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothState.TOAST,"Unable to connect device");
        msg.setData(bundle);
        activityHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        CCDTBluetoothService.this.start();
    }

    private void connectionLost() {
      //  FileWrite.Log("connectionLost","Log File","Bluetooth","CCDTBluetoothService", CCDTDiagApp.getAppContext());
        // Send message to calling activity to cause it to finish
        Message msg = activityHandler
                .obtainMessage(BluetoothState.MESSAGE_ABORT);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothState.TOAST,"connectionLost");
        msg.setData(bundle);
        activityHandler.sendMessage(msg);

        CCDTBluetoothService.this.stop();
    }

    // This thread runs while attempting to make an outgoing connection with a device.
    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice bluetoothDevice;

        public ConnectThread(BluetoothDevice device) {
            bluetoothDevice = device;
            BluetoothSocket tmp = null;
            try {
                // create the socket
                tmp = (BluetoothSocket) bluetoothDevice.getClass().getMethod("createRfcommSocket", new Class[]{int.class})
                        .invoke(bluetoothDevice, 1);

            } catch (Exception ex) {
                ex.printStackTrace();
            }
            socket = tmp;
        }

        public void run() {
            if (DEBUG)
                Log.i(TAG, "BEGIN mConnectThreads:");
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            adapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                if(!socket.isConnected())
                   socket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    socket.close();
                } catch (IOException e2) {
                    if (DEBUG)
                        Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (CCDTBluetoothService.this) {
                connectThread = null;
            }

            // Start the connected thread
            connected(socket, bluetoothDevice);
        }

        public void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                if (DEBUG)
                    Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device. It handles all
     * incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inStream;
        private final OutputStream outStream;

        public ConnectedThread(BluetoothSocket bluetoothSocket) {
            if (DEBUG)
                Log.d(TAG, "create ConnectedThread: ");
            socket = bluetoothSocket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = bluetoothSocket.getInputStream();
                tmpOut = bluetoothSocket.getOutputStream();
            } catch (IOException e) {
                if (DEBUG)
                    Log.e(TAG, "temp sockets not created", e);
            }

            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            if (DEBUG)
                Log.i(TAG, "BEGIN connectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {

                    bytes = inStream.read(buffer);
                    if (bytes != -1) {
                        activityHandler.obtainMessage(BluetoothState.MESSAGE_READ, bytes, -1, Arrays.copyOfRange(buffer, 0, bytes)).sendToTarget();
                    }
                } catch (IOException e) {
                    if (DEBUG)
                        Log.e(TAG, "disconnected", e);
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    // Start the service over to restart listening mode
                    CCDTBluetoothService.this.start();
                    break;
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                outStream.write(buffer);
                //Log.i(TAG,  Arrays.toString(buffer));
                activityHandler.obtainMessage(
                        BluetoothState.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();
            } catch (IOException e) {
                //if (DEBUG)
                    Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                if (inStream != null) {
                    inStream.close();
                }
                if (outStream != null) {
                    outStream.close();
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                if (DEBUG)
                    Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

}
