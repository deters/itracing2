package net.sylvek.itracing2;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import net.sylvek.itracing2.database.Devices;

/**
 * Created by sylvek on 18/05/2015.
 */
public class BluetoothLEService extends Service {

    public static final int NO_ALERT = 0x00;
    public static final int MEDIUM_ALERT = 0x01;
    public static final int HIGH_ALERT = 0x02;

    public static final String IMMEDIATE_ALERT_AVAILABLE = "IMMEDIATE_ALERT_AVAILABLE";
    public static final String GATT_CONNECTED = "GATT_CONNECTED";
    public static final String GATT_DISCONNECTED = "GATT_DISCONNECTED";
    public static final String SERVICES_DISCOVERED = "SERVICES_DISCOVERED";

    public static final UUID IMMEDIATE_ALERT_SERVICE = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb");
    public static final UUID FIND_ME_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID LINK_LOSS_SERVICE = UUID.fromString("00001803-0000-1000-8000-00805f9b34fb");
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID ALERT_LEVEL_CHARACTERISTIC = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");
    public static final UUID FIND_ME_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    public static final String TAG = BluetoothLEService.class.toString();
    public static final String ACTION_PREFIX = "net.sylvek.itracing2.action.";
    public static final int FOREGROUND_ID = 1664;
    public static final String OUT_OF_BAND = "out_of_band";
    public static final String DOUBLE_CLICK = "double-click";
    public static final String SIMPLE_CLICK = "simple-click";
    public static final String BROADCAST_INTENT_ACTION = "BROADCAST_INTENT";


    private HashMap<String, BluetoothGatt> bluetoothGatt = new HashMap<>();

    private HashMap<String, BluetoothGattService> immediateAlertService = new HashMap<>();


    private long lastChange;

    private Runnable r;

    private Handler handler = new Handler();

    private Object lock = new Object();


    private class CustomBluetoothGattCallback extends BluetoothGattCallback {

        private final String address;

        CustomBluetoothGattCallback(final String address) {
            this.address = address;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {


            if (BluetoothGatt.GATT_SUCCESS == status) {


                switch (newState) {

                    case BluetoothGatt.STATE_DISCONNECTED:
                        Log.d(TAG, "onConnectionStateChange() newstate = STATE_DISCONNECTED");

                        String action = Preferences.getActionOutOfBand(getApplicationContext(), this.address);
                        sendAction(OUT_OF_BAND, action);

                        ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                        toneGen1.startTone(ToneGenerator.TONE_CDMA_PIP, 1500);

                        Devices.setStatus(getApplicationContext(), this.address, Devices.STATUS_DISCONNECTED);
                        broadcaster.sendBroadcast(new Intent(GATT_DISCONNECTED).putExtra("address", this.address));

                        break;

                    case BluetoothGatt.STATE_CONNECTING:
                        Log.d(TAG, "onConnectionStateChange() newstate = STATE_CONNECTING");
                        break;

                    case BluetoothGatt.STATE_CONNECTED:
                        Log.d(TAG, "onConnectionStateChange() newstate = STATE_CONNECTED");

                        if (!immediateAlertService.containsKey(gatt.getDevice().getAddress())) {
                            gatt.discoverServices();
                        }

                        ToneGenerator toneGen2 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                        toneGen2.startTone(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 400);

                        Devices.setStatus(getApplicationContext(), this.address, Devices.STATUS_CONNECTED);
                        broadcaster.sendBroadcast(new Intent(GATT_CONNECTED).putExtra("address", this.address));
                        break;

                    case BluetoothGatt.STATE_DISCONNECTING:
                        Log.d(TAG, "onConnectionStateChange() newstate = STATE_DISCONNECTING");
                        break;

                }


            }

            final boolean actionOnPowerOff = Preferences.isActionOnPowerOff(BluetoothLEService.this, this.address);

            if (actionOnPowerOff || status == 8) {
                Log.d(TAG, "onConnectionStateChange() address: " + address + " newState => " + newState);
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    String action = Preferences.getActionOutOfBand(getApplicationContext(), this.address);
                    sendAction(OUT_OF_BAND, action);
                    enablePeerDeviceNotifyMe(gatt, false);
                }
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {

            synchronized (lock) {


                Log.d(TAG, "onServicesDiscovered()");

                broadcaster.sendBroadcast(new Intent(SERVICES_DISCOVERED));
                if (BluetoothGatt.GATT_SUCCESS == status) {

                    for (BluetoothGattService service : gatt.getServices()) {
                        if (IMMEDIATE_ALERT_SERVICE.equals(service.getUuid())) {

                            if (!immediateAlertService.containsKey(gatt.getDevice().getAddress())) {
                                immediateAlertService.put(gatt.getDevice().getAddress(), service);

                                Devices.setStatus(getApplicationContext(), this.address, Devices.STATUS_READY);
                                broadcaster.sendBroadcast(new Intent(IMMEDIATE_ALERT_AVAILABLE));
                                gatt.readCharacteristic(getCharacteristic(gatt, IMMEDIATE_ALERT_SERVICE, ALERT_LEVEL_CHARACTERISTIC));

                            }

                        }


                        if (FIND_ME_SERVICE.equals(service.getUuid())) {
                            if (!service.getCharacteristics().isEmpty()) {
                                BluetoothGattCharacteristic buttonCharacteristic;
                                buttonCharacteristic = service.getCharacteristics().get(0);
                                setCharacteristicNotification(gatt, buttonCharacteristic, true);
                            }
                        }
                    }
                    enablePeerDeviceNotifyMe(gatt, true);
                }


                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite()");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "onCharacteristicChanged()");
            final long delayDoubleClick = Preferences.getDoubleButtonDelay(getApplicationContext(), this.address);

            final long now = SystemClock.elapsedRealtime();
            if (lastChange + delayDoubleClick > now) {
                Log.d(TAG, "onCharacteristicChanged() - double click");


                ToneGenerator toneGen2 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                toneGen2.startTone(ToneGenerator.TONE_CDMA_PIP, 1000);

                ToneGenerator toneGen3 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                toneGen3.startTone(ToneGenerator.TONE_CDMA_PIP, 1000);



                lastChange = 0;
                handler.removeCallbacks(r);
                String action = Preferences.getActionDoubleButton(getApplicationContext(), this.address);
                sendAction(DOUBLE_CLICK, action);
            } else {
                lastChange = now;
                r = new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "onCharacteristicChanged() - simple click");

                        ToneGenerator toneGen2 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                        toneGen2.startTone(ToneGenerator.TONE_CDMA_PIP, 1000);

                        String action = Preferences.getActionSimpleButton(getApplicationContext(), CustomBluetoothGattCallback.this.address);
                        sendAction(SIMPLE_CLICK, action);
                    }
                };
                handler.postDelayed(r, delayDoubleClick);
            }
        }

        private void sendAction(String source, String action) {
            final Intent intent = new Intent(ACTION_PREFIX + action);
            intent.putExtra(Devices.ADDRESS, this.address);
            sendBroadcast(intent);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicRead()");
            if (characteristic.getValue() != null && characteristic.getValue().length > 0) {
            }
        }
    }

    private void setCharacteristicNotification(BluetoothGatt bluetoothgatt, BluetoothGattCharacteristic bluetoothgattcharacteristic, boolean flag) {
        bluetoothgatt.setCharacteristicNotification(bluetoothgattcharacteristic, flag);
        if (FIND_ME_CHARACTERISTIC.equals(bluetoothgattcharacteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = bluetoothgattcharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                bluetoothgatt.writeDescriptor(descriptor);
            }
        }
    }

    public void enablePeerDeviceNotifyMe(BluetoothGatt bluetoothgatt, boolean flag) {
        BluetoothGattCharacteristic bluetoothgattcharacteristic = getCharacteristic(bluetoothgatt, FIND_ME_SERVICE, FIND_ME_CHARACTERISTIC);
        if (bluetoothgattcharacteristic != null && (bluetoothgattcharacteristic.getProperties() | 0x10) > 0) {
            setCharacteristicNotification(bluetoothgatt, bluetoothgattcharacteristic, flag);
        }
    }

    private BluetoothGattCharacteristic getCharacteristic(BluetoothGatt bluetoothgatt, UUID serviceUuid, UUID characteristicUuid) {
        if (bluetoothgatt != null) {
            BluetoothGattService service = bluetoothgatt.getService(serviceUuid);
            if (service != null) {
                return service.getCharacteristic(characteristicUuid);
            }
        }

        return null;
    }

    public class BackgroundBluetoothLEBinder extends Binder {
        public BluetoothLEService service() {
            return BluetoothLEService.this;
        }
    }

    private BackgroundBluetoothLEBinder myBinder = new BackgroundBluetoothLEBinder();

    private LocalBroadcastManager broadcaster;

    @Override
    public IBinder onBind(Intent intent) {
        return myBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.setForegroundEnabled(Preferences.isForegroundEnabled(this));
        this.connect();
        return START_STICKY;
    }

    public void setForegroundEnabled(boolean enabled) {
        if (enabled) {
            final Notification notification = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(getText(R.string.app_name))
                    .setTicker(getText(R.string.foreground_started))
                    .setContentText(getText(R.string.foreground_started))
                    .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, DevicesActivity.class), 0))
                    .setShowWhen(false).setAutoCancel(true).build();
            this.startForeground(FOREGROUND_ID, notification);
        } else {
            this.stopForeground(true);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate()");
        this.broadcaster = LocalBroadcastManager.getInstance(this);
    }

    @Override
    public void onDestroy() {

        super.onDestroy();
        Log.d(TAG, "onDestroy()");
    }

    public void immediateAlert(String address, int alertType) {
        Log.d(TAG, "immediateAlert() - the device " + address);

        if (this.bluetoothGatt.get(address) == null) {
            Log.d(TAG, "immediateAlert() NO GAT FOR ADDRESS");

            return;
        }


        BluetoothGattService alertService = immediateAlertService.get(address);

        if (alertService == null) {

            Log.d(TAG, "immediateAlert() NO ALERT SERVICE FOR ADDRESS");

            this.bluetoothGatt.get(address).discoverServices();

            return;
        }

        List<BluetoothGattCharacteristic> characteristics = alertService.getCharacteristics();

        if (characteristics == null || alertService.getCharacteristics().size() == 0) {
            Log.d(TAG, "immediateAlert() NO CHARACTERISTICS FOR IMMEDIATE ALERT SERVICE");
            //somethingGoesWrong();
        }


        Log.d(TAG, "immediateAlert() immediate alert service have " + characteristics.size() + " characteristics.");

        final BluetoothGattCharacteristic characteristic = characteristics.get(0);


        characteristic.setValue(alertType, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        this.bluetoothGatt.get(address).writeCharacteristic(characteristic);
    }

    private synchronized void somethingGoesWrong() {
        Toast.makeText(this, R.string.something_goes_wrong, Toast.LENGTH_LONG).show();
    }

    public void connect() {
        Log.d(TAG, "connect()");
        final Cursor cursor = Devices.findDevices(this);
        if (cursor != null && cursor.getCount() > 0) {
            cursor.moveToFirst();
            do {
                final String address = cursor.getString(0);
                if (Devices.isEnabled(this, address)) {

                    this.connect(address);
                }
            } while (cursor.moveToNext());
        }
    }


    private boolean refreshDeviceCache(BluetoothGatt gatt) {
        try {
            BluetoothGatt localBluetoothGatt = gatt;
            Method localMethod = localBluetoothGatt.getClass().getMethod("refresh", new Class[0]);
            if (localMethod != null) {
                boolean bool = ((Boolean) localMethod.invoke(localBluetoothGatt, new Object[0])).booleanValue();
                return bool;
            }
        } catch (Exception localException) {
            Log.e(TAG, "An exception occured while refreshing bluetooth cache device");
        }
        return false;
    }

    public void connect(final String address) {

        synchronized (lock) {


            Log.d(TAG, "connect(" + address + ")");


            //if (this.bluetoothGatt.containsKey(address)) {
            disconnect(address);

            //}


            BluetoothDevice mDevice;

            mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
            BluetoothGatt gatt = mDevice.connectGatt(this, true, new CustomBluetoothGattCallback(address));


            gatt.connect();

            this.bluetoothGatt.put(address, gatt);
            //gatt.discoverServices();
            //} else {


            //  Log.d(TAG, "connect() - discovering services for " + address);
            //this.bluetoothGatt.get(address).discoverServices();
            //}


        }

    }

    public synchronized void disconnect(final String address) {

        //if (this.immediateAlertService.containsKey(address)){
        //    this.immediateAlertService.remove(address);
        //}


        if (this.bluetoothGatt.containsKey(address)) {
            this.bluetoothGatt.get(address).disconnect();

            this.bluetoothGatt.remove(address);
        }
         /*
            Log.d(TAG, "disconnect() - to device " + address);
            if (!Devices.isEnabled(this, address)) {
                Log.d(TAG, "disconnect() - no background linked for " + address);
                if (this.bluetoothGatt.get(address) != null) {
                    this.bluetoothGatt.get(address).disconnect();
                }
                this.bluetoothGatt.remove(address);
            }
        }*/
    }

    public synchronized void remove(final String address) {

        if (this.immediateAlertService.containsKey(address)) {
            this.immediateAlertService.remove(address);
        }

        if (this.bluetoothGatt.containsKey(address)) {
            Log.d(TAG, "remove() - to device " + address);
            if (this.bluetoothGatt.get(address) != null) {
                this.bluetoothGatt.get(address).disconnect();
            }
            this.bluetoothGatt.remove(address);
        }
    }
}
