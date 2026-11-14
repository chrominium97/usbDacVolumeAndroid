package com.example.libusbAndroidTest;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;

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
    private boolean launchedByAttach;

    // Volume slider bounds until the adapter's own range has been read once (during an apply).
    private static final float DEFAULT_MIN_DB = -60f, DEFAULT_MAX_DB = 0f, SLIDER_STEP_DB = 0.5f;

    private TextView deviceName;
    private TextView deviceState;
    private LinearProgressIndicator progress;
    private Slider volumeSlider;
    private TextView volumeValue;
    private MaterialButton applyButton;
    private MaterialSwitch autoApply;
    private MaterialSwitch quitAfterApply;
    private View micCard;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                onDeviceReady(device);
            } else {
                Log.w(TAG, "USB permission denied for " + device);
                setState(device, getString(R.string.state_permission_denied), false, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        deviceName = binding.deviceName;
        deviceState = binding.deviceState;
        progress = binding.progress;
        volumeSlider = binding.volumeSlider;
        volumeValue = binding.volumeValue;
        applyButton = binding.applyButton;
        autoApply = binding.autoApply;
        quitAfterApply = binding.quitAfterApply;
        micCard = binding.micCard;

        SharedPreferences settings = prefs();
        setSliderRange(settings.getFloat("rangeMinDb", DEFAULT_MIN_DB), settings.getFloat("rangeMaxDb", DEFAULT_MAX_DB),
                savedVolumeDb(settings));
        volumeSlider.setLabelFormatter(value -> getString(R.string.volume_value, db(value)));
        volumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            volumeValue.setText(getString(R.string.volume_value, db(value)));
            if (fromUser) prefs().edit().putFloat("volumeDb", value).apply();
        });
        volumeValue.setText(getString(R.string.volume_value, db(volumeSlider.getValue())));

        autoApply.setChecked(settings.getBoolean("autoApply", false));
        quitAfterApply.setChecked(settings.getBoolean("quitAfterApply", false));
        autoApply.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) cancelPendingApply();
            prefs().edit().putBoolean("autoApply", checked).apply();
        });
        quitAfterApply.setOnCheckedChangeListener((button, checked) ->
                prefs().edit().putBoolean("quitAfterApply", checked).apply());
        applyButton.setOnClickListener(this::applyButtonPressed);
        binding.micButton.setOnClickListener(view -> ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, CALLBACK_PERMISSION));
        updateMicCard();

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
        // "Apply when connected" means a plug-in, not every time the app is opened: applying
        // pauses whatever is playing.
        launchedByAttach = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction());
        if (launchedByAttach) {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (device == null || !hasAudioControl(device)) {
            device = findAudioDevice();
        }
        if (device == null) {
            dac = null;
            setState(null, getString(R.string.no_device_hint), false, false);
            return;
        }

        dac = device;
        if (usbManager.hasPermission(device)) {
            onDeviceReady(device);
        } else {
            setState(device, getString(R.string.state_waiting_permission), true, false);
            Intent permission = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, permission, flags));
        }
    }

    private void onDeviceReady(UsbDevice device) {
        dac = device;
        setState(device, getString(R.string.state_connected), false, false);
        if (autoApply.isChecked() && launchedByAttach) {
            launchedByAttach = false;
            autoApplyWhenAudioReady(device);
        }
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
        setState(device, getString(R.string.state_waiting_audio), true, false);
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
        setState(device, getString(R.string.state_pausing), true, false);

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
                if (ok && !idle) setState(device, getString(R.string.state_playback_kept_running), false, true);
                if (ok && quitWhenDone) {
                    Toast.makeText(getApplicationContext(), getString(R.string.toast_applied,
                            db(volumeSlider.getValue())), Toast.LENGTH_SHORT).show();
                    finishAndRemoveTask();
                }
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

    private void applyButtonPressed(View view) {
        UsbDevice device = findAudioDevice();
        if (device == null || !usbManager.hasPermission(device)) {
            handleIntent(new Intent()); // shows "no device" or asks for permission
            return;
        }
        applyWhenOutputIdle(device, false);
    }

    /**
     * Updates the device card. {@code device} null means no adapter; {@code busy} shows the
     * progress bar and disables Apply; {@code error} colors the state line.
     */
    private void setState(UsbDevice device, String state, boolean busy, boolean error) {
        deviceName.setText(device == null ? getString(R.string.no_device) : String.valueOf(device.getProductName()));
        deviceState.setText(state);
        deviceState.setTextColor(MaterialColors.getColor(deviceState,
                error ? androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorOnSurfaceVariant));
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        applyButton.setEnabled(device != null && !busy);
    }

    private void updateMicCard() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        micCard.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    /** The saved volume; older versions stored the raw little-endian request bytes as hex. */
    private static float savedVolumeDb(SharedPreferences settings) {
        if (settings.contains("volumeDb")) return settings.getFloat("volumeDb", DEFAULT_MAX_DB);
        String hex = settings.getString("volume", "0000");
        if (!hex.matches("[0-9A-Fa-f]{4}")) return DEFAULT_MAX_DB;
        return le16(hexStringToByteArray(hex), 0) / 256f;
    }

    /** Sets the slider bounds to the adapter's range, snapped inward to the slider's step. */
    private void setSliderRange(float minDb, float maxDb, float valueDb) {
        float from = (float) Math.ceil(minDb / SLIDER_STEP_DB) * SLIDER_STEP_DB;
        float to = (float) Math.floor(maxDb / SLIDER_STEP_DB) * SLIDER_STEP_DB;
        if (to <= from) {
            from = DEFAULT_MIN_DB;
            to = DEFAULT_MAX_DB;
        }
        float value = Math.round(Math.max(from, Math.min(to, valueDb)) / SLIDER_STEP_DB) * SLIDER_STEP_DB;
        volumeSlider.setValueFrom(Math.min(from, volumeSlider.getValueFrom()));
        volumeSlider.setValueTo(Math.max(to, volumeSlider.getValueTo()));
        volumeSlider.setValue(value);
        volumeSlider.setValueFrom(from);
        volumeSlider.setValueTo(to);
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
        int target = Math.round(volumeSlider.getValue() * 256);
        byte[] volume = {(byte) target, (byte) (target >> 8)};

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            setState(device, getString(R.string.state_open_failed), false, true);
            return false;
        }

        UsbConfiguration config = activeConfiguration(device, connection);
        List<UsbInterface> audio = audioInterfaces(config);
        UsbInterface control = audio.isEmpty() ? null : audio.get(0);
        if (control == null || control.getInterfaceSubclass() != AUDIO_SUBCLASS_CONTROL) {
            connection.close();
            setState(device, getString(R.string.state_no_audio_interface), false, true);
            return false;
        }

        boolean uac1 = control.getInterfaceProtocol() == UAC1_PROTOCOL;
        int wIndex = (FEATURE_UNIT_ID << 8) | control.getId();
        int channelsSet = 0;
        Integer firstBefore = null, firstAfter = null;
        int[] range = null;
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
                int[] channelRange = volumeRange(connection, uac1, wValue, wIndex);

                Log.i(TAG, String.format(Locale.US, "ch%d: %s → %s dB (range %s..%s)%s", channel,
                        db(before), db(after), channelRange == null ? "?" : db(Integer.valueOf(channelRange[0])),
                        channelRange == null ? "?" : db(Integer.valueOf(channelRange[1])), sent == volume.length ? "" : " SET FAILED"));
                if (sent == volume.length) channelsSet++;
                if (firstBefore == null) {
                    firstBefore = before;
                    firstAfter = after;
                }
                if (range == null) range = channelRange;
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

        if (range != null) {
            prefs().edit().putFloat("rangeMinDb", range[0] / 256f).putFloat("rangeMaxDb", range[1] / 256f).apply();
            setSliderRange(range[0] / 256f, range[1] / 256f, volumeSlider.getValue());
        }
        boolean ok = channelsSet == CHANNELS.length && driverBack;
        if (!driverBack) {
            setState(device, getString(R.string.state_driver_missing), false, true);
        } else if (channelsSet != CHANNELS.length) {
            setState(device, getString(R.string.state_set_failed), false, true);
        } else {
            setState(device, getString(R.string.state_set, uiDb(firstAfter), uiDb(firstBefore)), false, false);
        }
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

    /** {min, max} in 1/256 dB, or null if the adapter does not report it. */
    private static int[] volumeRange(UsbDeviceConnection connection, boolean uac1, int wValue, int wIndex) {
        if (uac1) {
            byte[] min = new byte[2];
            byte[] max = new byte[2];
            int a = connection.controlTransfer(REQUEST_TYPE_IN, UAC1_GET_MIN, wValue, wIndex, min, 2, TIMEOUT_MS);
            int b = connection.controlTransfer(REQUEST_TYPE_IN, UAC1_GET_MAX, wValue, wIndex, max, 2, TIMEOUT_MS);
            return a == 2 && b == 2 ? new int[]{le16(min, 0), le16(max, 0)} : null;
        }
        // wNumSubRanges followed by MIN, MAX, RES of the first subrange
        byte[] range = new byte[8];
        int read = connection.controlTransfer(REQUEST_TYPE_IN, UAC2_RANGE, wValue, wIndex, range, range.length, TIMEOUT_MS);
        return read == range.length ? new int[]{le16(range, 2), le16(range, 4)} : null;
    }

    private static int le16(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
    }

    /** Formats a raw 1/256 dB value. */
    private static String db(Integer value) {
        return value == null ? "?" : String.format(Locale.US, "%.1f", value / 256.0);
    }

    /** Formats a raw 1/256 dB value for the screen. */
    private static String uiDb(Integer raw) {
        return raw == null ? "?" : db(raw / 256f);
    }

    /** Formats a dB value for the screen, with a real minus sign. */
    private static String db(float value) {
        return String.format(Locale.US, "%.1f", value).replace('-', '\u2212');
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CALLBACK_PERMISSION) updateMicCard();
    }
}
