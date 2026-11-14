package com.example.libusbAndroidTest;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "UsbDacVolume";
    private static final String ACTION_USB_PERMISSION = "com.example.libusbAndroidTest.USB_PERMISSION";

    // USB Audio Class volume requests. The value is a signed 16-bit number in 1/256 dB,
    // sent little-endian, so "0000" is 0 dB.
    private static final int AUDIO_SUBCLASS_CONTROL = 1;
    private static final int UAC1_PROTOCOL = 0x00;
    private static final int REQUEST_TYPE_OUT = 0x21; // class request, interface recipient, host to device
    private static final int REQUEST_TYPE_IN = 0xA1;  // class request, interface recipient, device to host
    private static final int UAC1_SET_CUR = 0x01, UAC1_GET_CUR = 0x81, UAC1_GET_MIN = 0x82, UAC1_GET_MAX = 0x83;
    private static final int UAC2_CUR = 0x01, UAC2_RANGE = 0x02; // UAC2 and UAC3 use the same codes
    private static final int VOLUME_CONTROL = 0x02;
    // The playback feature unit: ID 2 on the Apple adapter, and fixed at 2 by the UAC3 BADD
    // headphone and speaker profiles.
    private static final int FEATURE_UNIT_ID = 2;
    private static final int[] CHANNELS = {1, 2};
    private static final int TIMEOUT_MS = 500;
    // How long nothing may play before the driver is detached. The output went to standby 1.9 s
    // after a pause; this also covers the audio policy finishing with a just-connected device.
    private static final long OUTPUT_IDLE_MS = 3000;
    private static final long MAX_IDLE_WAIT_MS = 10000;
    private static final long IDLE_POLL_MS = 200;

    private UsbManager usbManager;
    private AudioManager audioManager;
    private UsbDevice dac;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioDeviceCallback audioDeviceWait;
    private Runnable pendingApply;
    private AudioFocusRequest focusRequest;
    private long quietSince;

    private TextView status;
    private EditText volInput;
    private CheckBox autoApply;
    private CheckBox quitAfterApply;
    private Button recordPermissionButton;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                onDeviceReady(device);
            } else {
                Log.w(TAG, "USB permission denied for " + device);
                status.setText("USB permission denied");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        status = binding.sampleText;
        volInput = binding.volume;
        autoApply = binding.autoApply;
        quitAfterApply = binding.quitAfterApply;
        recordPermissionButton = binding.recordPermissionButton;

        SharedPreferences settings = prefs();
        volInput.setText(settings.getString("volume", "0000"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        quitAfterApply.setChecked(settings.getBoolean("quitAfterApply", false));
        recordPermissionButton.setEnabled(ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        cancelPendingApply();
        unregisterReceiver(usbPermissionReceiver);
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        UsbDevice device = null;
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (device == null || !hasAudioControl(device)) {
            device = findAudioDevice();
        }
        if (device == null) {
            dac = null;
            status.setText("No USB audio device.\nConnect the adapter with headphones plugged in.");
            return;
        }

        dac = device;
        if (usbManager.hasPermission(device)) {
            onDeviceReady(device);
        } else {
            status.setText("Waiting for USB permission…");
            Intent permission = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, permission, flags));
        }
    }

    private void onDeviceReady(UsbDevice device) {
        dac = device;
        status.setText(String.valueOf(device.getProductName()));
        if (autoApply.isChecked()) autoApplyWhenAudioReady(device);
    }

    /**
     * Android connects a USB audio device to the audio HAL right after it reports the USB attach,
     * which is also when this app gets launched. If the kernel driver is detached while that is
     * still going on, the HAL finds no sound card and binds the new USB output to the speaker
     * ("ignore set device to non existing USB card"). So wait until the audio service lists the
     * USB output, which happens once the audio policy has opened its outputs.
     */
    private void autoApplyWhenAudioReady(UsbDevice device) {
        cancelPendingApply();
        status.setText(device.getProductName() + "\nWaiting for Android to connect the audio output…");
        audioDeviceWait = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                // Also called right after registering, with the devices already connected.
                for (AudioDeviceInfo info : added) {
                    if (info.isSink() && (info.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                            || info.getType() == AudioDeviceInfo.TYPE_USB_DEVICE)) {
                        Log.i(TAG, "USB audio output registered (" + info.getAddress() + ")");
                        applyWhenOutputIdle(device, quitAfterApply.isChecked());
                        return;
                    }
                }
            }
        };
        audioManager.registerAudioDeviceCallback(audioDeviceWait, handler);
    }

    /**
     * Applies the volume once nothing is playing.
     *
     * On this phone (Qualcomm USB audio offload) the DSP sets up its path to the USB sound card
     * when a stream starts. Detaching the driver recreates the card, and a stream that was playing
     * across that keeps "playing" into the old card: silence, with no error, until the player is
     * paused long enough for the output to go to standby. So ask the player to pause (transient
     * audio focus), wait for the output to idle, apply, and hand the focus back so the player
     * resumes on a fresh stream.
     */
    private void applyWhenOutputIdle(UsbDevice device, boolean quitWhenDone) {
        cancelPendingApply();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(change -> { }, handler)
                .build();
        boolean granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.i(TAG, "transient audio focus " + (granted ? "granted" : "not granted") + ", waiting for the output to idle");
        status.setText(device.getProductName() + "\nPausing playback to apply the volume…");

        long started = SystemClock.elapsedRealtime();
        quietSince = -1;
        pendingApply = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                if (audioManager.isMusicActive()) {
                    quietSince = -1;
                } else if (quietSince < 0) {
                    quietSince = now;
                }
                boolean idle = quietSince >= 0 && now - quietSince >= OUTPUT_IDLE_MS;
                boolean gaveUp = now - started >= MAX_IDLE_WAIT_MS;
                if (!idle && !gaveUp) {
                    handler.postDelayed(this, IDLE_POLL_MS);
                    return;
                }
                pendingApply = null;
                if (!idle) Log.w(TAG, "playback did not stop within " + MAX_IDLE_WAIT_MS + " ms, applying anyway");
                boolean ok = applyVolume(device);
                abandonFocus();
                if (!idle) status.append("\nPlayback kept running: if the sound is gone, pause and resume it.");
                if (ok && quitWhenDone) finishAndRemoveTask();
            }
        };
        handler.post(pendingApply);
    }

    private void cancelPendingApply() {
        if (audioDeviceWait != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceWait);
            audioDeviceWait = null;
        }
        if (pendingApply != null) {
            handler.removeCallbacks(pendingApply);
            pendingApply = null;
        }
        abandonFocus();
    }

    private void abandonFocus() {
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    public void applyButtonPressed(View view) {
        UsbDevice device = findAudioDevice();
        if (device == null || !usbManager.hasPermission(device)) {
            status.setBackgroundColor(Color.RED);
            if (device != null) handleIntent(new Intent()); // asks for permission
            return;
        }
        status.setBackgroundColor(Color.TRANSPARENT);
        if (parseVolume() == null) return;

        prefs().edit().putString("volume", volInput.getText().toString().trim()).apply();
        applyWhenOutputIdle(device, false);
    }

    public void autoApplyCheckboxPressed(View view) {
        if (!autoApply.isChecked()) cancelPendingApply();
        prefs().edit().putBoolean("autoApply", autoApply.isChecked()).apply();
    }

    public void quitAfterApplyCheckboxPressed(View view) {
        prefs().edit().putBoolean("quitAfterApply", quitAfterApply.isChecked()).apply();
    }

    private SharedPreferences prefs() {
        return getApplicationContext().getSharedPreferences("myPrefs", 0);
    }

    /**
     * Sets the DAC's hardware volume without resetting the device.
     *
     * The audio class requests go to the audio control interface, which the kernel audio driver
     * owns, so the driver is detached from every audio interface, the requests are sent, and the
     * interfaces are released so the driver binds again. Resetting the device instead makes the
     * Apple adapter re-enumerate, which relaunches this app and loses the new volume.
     */
    private boolean applyVolume(UsbDevice device) {
        byte[] volume = parseVolume();
        if (volume == null) return false;

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            status.setText("Could not open " + device.getProductName());
            return false;
        }

        UsbConfiguration config = activeConfiguration(device, connection);
        List<UsbInterface> audio = audioInterfaces(config);
        UsbInterface control = audio.isEmpty() ? null : audio.get(0);
        if (control == null || control.getInterfaceSubclass() != AUDIO_SUBCLASS_CONTROL) {
            connection.close();
            status.setText(device.getProductName() + "\nNo audio interface in the active configuration.");
            return false;
        }

        boolean uac1 = control.getInterfaceProtocol() == UAC1_PROTOCOL;
        int wIndex = (FEATURE_UNIT_ID << 8) | control.getId();
        StringBuilder report = new StringBuilder();
        int channelsSet = 0;
        Log.i(TAG, String.format(Locale.US, "%s: configuration %d, %s, control interface %d",
                device.getProductName(), config.getId(), uac1 ? "UAC1" : "UAC2/3", control.getId()));

        try {
            for (UsbInterface intf : audio) {
                if (!connection.claimInterface(intf, true)) {
                    Log.w(TAG, "could not claim interface " + intf.getId());
                }
            }
            for (int channel : CHANNELS) {
                int wValue = (VOLUME_CONTROL << 8) | channel;
                Integer before = getVolume(connection, uac1, wValue, wIndex);
                int sent = connection.controlTransfer(REQUEST_TYPE_OUT, uac1 ? UAC1_SET_CUR : UAC2_CUR,
                        wValue, wIndex, volume.clone(), volume.length, TIMEOUT_MS);
                Integer after = getVolume(connection, uac1, wValue, wIndex);
                String range = volumeRange(connection, uac1, wValue, wIndex);

                String line = String.format(Locale.US, "ch%d: %s → %s dB (range %s)%s", channel,
                        db(before), db(after), range, sent == volume.length ? "" : " SET FAILED");
                Log.i(TAG, line);
                report.append('\n').append(line);
                if (sent == volume.length) channelsSet++;
            }
        } finally {
            // releaseInterface() also asks the kernel to re-bind its driver. The audio driver only
            // binds to the control interface and claims the streaming interfaces from there, so
            // those must be free first: release in reverse, control interface last.
            for (int i = audio.size() - 1; i >= 0; i--) {
                connection.releaseInterface(audio.get(i));
            }
        }
        boolean driverBack = audioDriverOwnsAll(connection, audio);
        connection.close();

        boolean ok = channelsSet == CHANNELS.length && driverBack;
        if (!driverBack) report.append("\nAudio driver did not come back: unplug and replug the headphones.");
        status.setText(device.getProductName() + report);
        Toast.makeText(getApplicationContext(), ok ? "Volume set for DAC!" : "Could not set the DAC volume",
                Toast.LENGTH_LONG).show();
        return ok;
    }

    /**
     * True if every audio interface is bound to a kernel driver again. A non-forced claim fails
     * on an interface that a driver owns; one that succeeds is released again right away.
     */
    private static boolean audioDriverOwnsAll(UsbDeviceConnection connection, List<UsbInterface> audio) {
        boolean all = true;
        StringBuilder state = new StringBuilder("kernel driver after release:");
        for (int i = audio.size() - 1; i >= 0; i--) {
            UsbInterface intf = audio.get(i);
            boolean free = connection.claimInterface(intf, false);
            if (free) connection.releaseInterface(intf);
            all &= !free;
            state.append(String.format(Locale.US, " if%d=%s", intf.getId(), free ? "FREE" : "bound"));
        }
        Log.i(TAG, state.toString());
        return all;
    }

    private byte[] parseVolume() {
        String hex = volInput.getText().toString().trim();
        if (!hex.matches("[0-9A-Fa-f]{4}")) {
            volInput.setBackgroundColor(Color.RED);
            return null;
        }
        volInput.setBackgroundColor(Color.TRANSPARENT);
        return hexStringToByteArray(hex);
    }

    private UsbDevice findAudioDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (hasAudioControl(device)) return device;
        }
        return null;
    }

    /** True if any configuration has an audio control interface (the adapter has none without headphones). */
    private static boolean hasAudioControl(UsbDevice device) {
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            List<UsbInterface> audio = audioInterfaces(device.getConfiguration(c));
            if (!audio.isEmpty() && audio.get(0).getInterfaceSubclass() == AUDIO_SUBCLASS_CONTROL) return true;
        }
        return false;
    }

    /**
     * UsbDevice.getInterface() flattens every configuration, and the Apple adapter has three
     * (UAC2 plus two UAC3), so ask the device which one the kernel selected.
     */
    private static UsbConfiguration activeConfiguration(UsbDevice device, UsbDeviceConnection connection) {
        byte[] value = new byte[1];
        int read = connection.controlTransfer(UsbConstants.USB_DIR_IN, 0x08 /* GET_CONFIGURATION */,
                0, 0, value, 1, TIMEOUT_MS);
        if (read == 1) {
            for (int c = 0; c < device.getConfigurationCount(); c++) {
                if (device.getConfiguration(c).getId() == (value[0] & 0xff)) return device.getConfiguration(c);
            }
        }
        Log.w(TAG, "could not read the active configuration, using the first one");
        return device.getConfiguration(0);
    }

    /** One entry per audio interface number, the control interface first. */
    private static List<UsbInterface> audioInterfaces(UsbConfiguration config) {
        List<UsbInterface> control = new ArrayList<>();
        List<UsbInterface> others = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < config.getInterfaceCount(); i++) {
            UsbInterface intf = config.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO || !seen.add(intf.getId())) continue;
            (intf.getInterfaceSubclass() == AUDIO_SUBCLASS_CONTROL ? control : others).add(intf);
        }
        control.addAll(others);
        return control;
    }

    private static Integer getVolume(UsbDeviceConnection connection, boolean uac1, int wValue, int wIndex) {
        byte[] value = new byte[2];
        int read = connection.controlTransfer(REQUEST_TYPE_IN, uac1 ? UAC1_GET_CUR : UAC2_CUR,
                wValue, wIndex, value, value.length, TIMEOUT_MS);
        return read == 2 ? le16(value, 0) : null;
    }

    private static String volumeRange(UsbDeviceConnection connection, boolean uac1, int wValue, int wIndex) {
        if (uac1) {
            byte[] min = new byte[2];
            byte[] max = new byte[2];
            int a = connection.controlTransfer(REQUEST_TYPE_IN, UAC1_GET_MIN, wValue, wIndex, min, 2, TIMEOUT_MS);
            int b = connection.controlTransfer(REQUEST_TYPE_IN, UAC1_GET_MAX, wValue, wIndex, max, 2, TIMEOUT_MS);
            return a == 2 && b == 2 ? db(le16(min, 0)) + ".." + db(le16(max, 0)) : "?";
        }
        // wNumSubRanges followed by MIN, MAX, RES of the first subrange
        byte[] range = new byte[8];
        int read = connection.controlTransfer(REQUEST_TYPE_IN, UAC2_RANGE, wValue, wIndex, range, range.length, TIMEOUT_MS);
        return read == range.length ? db(le16(range, 2)) + ".." + db(le16(range, 4)) : "?";
    }

    private static int le16(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
    }

    private static String db(Integer value) {
        return value == null ? "?" : String.format(Locale.US, "%.1f", value / 256.0);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static final int CALLBACK_PERMISSION = 1;

    public void recordPermissionButtonPressed(View view) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, CALLBACK_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CALLBACK_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recordPermissionButton.setEnabled(false);
        }
    }
}
