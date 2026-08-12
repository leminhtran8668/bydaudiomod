package com.bydaudiomod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.SparseIntArray;

import android.hardware.bydauto.adas.AbsBYDAutoADASListener;
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener;
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener;
import android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener;
import android.hardware.bydauto.energy.AbsBYDAutoEnergyListener;
import android.hardware.bydauto.radar.AbsBYDAutoRadarListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BydAutoService extends Service {
    private static final String TAG = "BydAutoService";
    public static volatile boolean isRunning = false;
    private static final String CHANNEL_ID = "BydAutoServiceChannel";

    private static final String PREF_NAME = "AdasNotiPrefs";
    private static final String PREF_KEY_BSD_STATE = "last_bsd_state";

    private List<Object> devices = new ArrayList<>();
    private List<Object> listeners = new ArrayList<>();
    
    private SoundPool soundPool;
    private final SparseIntArray soundMap = new SparseIntArray();
    
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.d(TAG, "onAudioFocusChange: " + focusChange);
        }
    };
    private final Handler audioFocusHandler = new Handler(Looper.getMainLooper());
    private Runnable abandonFocusRunnable;
    private final SparseIntArray soundDurationMap = new SparseIntArray();
    private boolean hasAudioFocus = false;
    
    private Map<String, Integer> featureNameToIdMap = new HashMap<>();

    // State tracking
    private int lastGearValue = -1;
    private int lastAccValue = -1;
    private int lastRadarValue = -1;
    private int lastBsdValue = 1; // 1: Standby
    private int lastEnergyValue = -1;
    private int lastEnergyRecycleValue = -1;
    private int lastOperationMode = -1;
    private boolean isAccActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Log.d(TAG, "onCreate: Starting BydAutoService");
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try {
            lastBsdValue = Integer.parseInt(prefs.getString(PREF_KEY_BSD_STATE, "1"));
        } catch (NumberFormatException ignored) {}
        
        createNotificationChannel();
        initSoundPool();
        
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BYD Audio Feedback Service")
                .setContentText("Monitoring vehicle status for audible alerts")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
                
        startForeground(2, notification);
        
        loadFeatureIds();
        initDevices();
    }

    private void initSoundPool() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build();

        loadSound(R.raw.adas_blind_spot_assist_0_off);
        loadSound(R.raw.adas_blind_spot_assist_1_on);
        loadSound(R.raw.energy_recycle_2_standard);
        loadSound(R.raw.energy_recycle_3_high);
        loadSound(R.raw.energy_road_surface_1_common);
        loadSound(R.raw.energy_road_surface_2_snow);
        loadSound(R.raw.gear_0_n);
        loadSound(R.raw.gear_1_r);
        loadSound(R.raw.gear_2_d);
        loadSound(R.raw.gear_3_p);
        loadSound(R.raw.cruise_control_off);
        loadSound(R.raw.cruise_control_on);
        loadSound(R.raw.operation_mode_1_sports_mode_off);
        loadSound(R.raw.operation_mode_2_sports_mode_on);
        loadSound(R.raw.reverse_radar_0_off);
        loadSound(R.raw.reverse_radar_1_on);
    }

    private void loadSound(int resId) {
        try {
            soundMap.put(resId, soundPool.load(this, resId, 1));
            soundDurationMap.put(resId, getSoundDuration(resId));
        } catch (Exception e) {
            Log.w(TAG, "Failed to load sound resId=" + resId + ": " + e.getMessage());
        }
    }

    private int getSoundDuration(int resId) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = getResources().openRawResourceFd(resId);
            if (afd != null) {
                retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    int duration = Integer.parseInt(durationStr);
                    Log.d(TAG, "getSoundDuration: resId=" + resId + ", duration=" + duration + "ms");
                    return duration;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get sound duration for resId=" + resId, e);
        } finally {
            try {
                if (afd != null) {
                    afd.close();
                }
            } catch (Exception ignored) {}
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return 2000; // default 2 seconds
    }

    private void playRawAudio(int resId) {
        int soundId = soundMap.get(resId);
        if (soundId != 0 && soundPool != null) {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            boolean shouldPlay = true;
            String soundType = "unknown";
            
            if (resId == R.raw.gear_0_n || resId == R.raw.gear_1_r || resId == R.raw.gear_2_d || resId == R.raw.gear_3_p) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_GEAR_SOUND, true);
                soundType = "gear";
            } else if (resId == R.raw.cruise_control_off || resId == R.raw.cruise_control_on) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_ACC_SOUND, true);
                soundType = "acc";
            } else if (resId == R.raw.adas_blind_spot_assist_0_off || resId == R.raw.adas_blind_spot_assist_1_on) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_BSD_SOUND, true);
                soundType = "bsd";
            } else if (resId == R.raw.reverse_radar_0_off || resId == R.raw.reverse_radar_1_on) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_RADAR_SOUND, true);
                soundType = "radar";
            } else if (resId == R.raw.energy_recycle_2_standard || resId == R.raw.energy_recycle_3_high) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_ENERGY_RECYCLE_SOUND, true);
                soundType = "energy_recycle";
            } else if (resId == R.raw.energy_road_surface_1_common || resId == R.raw.energy_road_surface_2_snow) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_ENERGY_ROAD_SOUND, true);
                soundType = "energy_road";
            } else if (resId == R.raw.operation_mode_1_sports_mode_off || resId == R.raw.operation_mode_2_sports_mode_on) {
                shouldPlay = prefs.getBoolean(ConfigData.KEY_OPERATION_MODE_SOUND, true);
                soundType = "operation_mode";
            }
            
            Log.d(TAG, "playRawAudio: resId=" + resId + ", soundId=" + soundId + ", type=" + soundType + ", shouldPlay=" + shouldPlay);
            if (shouldPlay) {
                if (audioManager != null) {
                    int result;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (audioFocusRequest == null) {
                            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build();
                             audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                    .setAudioAttributes(playbackAttributes)
                                    .setAcceptsDelayedFocusGain(false)
                                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                                    .build();
                        }
                        result = audioManager.requestAudioFocus(audioFocusRequest);
                    } else {
                        result = audioManager.requestAudioFocus(audioFocusChangeListener,
                                AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    }
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        hasAudioFocus = true;
                        Log.d(TAG, "Audio focus granted. Ducking background audio.");
                    } else {
                        Log.w(TAG, "Audio focus request failed.");
                    }
                }

                int playId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
                Log.d(TAG, "playRawAudio: play started, return streamId=" + playId);

                if (abandonFocusRunnable != null) {
                    audioFocusHandler.removeCallbacks(abandonFocusRunnable);
                }
                int duration = soundDurationMap.get(resId, 2000);
                abandonFocusRunnable = new Runnable() {
                    @Override
                    public void run() {
                        abandonAudioFocus();
                    }
                };
                audioFocusHandler.postDelayed(abandonFocusRunnable, duration + 500);
            }
        } else {
            Log.w(TAG, "playRawAudio: soundId is 0 or soundPool is null. resId=" + resId);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null && hasAudioFocus) {
            Log.d(TAG, "Abandoning audio focus.");
            int result;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest != null) {
                    result = audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
            } else {
                result = audioManager.abandonAudioFocus(audioFocusChangeListener);
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasAudioFocus = false;
            } else {
                Log.w(TAG, "Failed to abandon audio focus.");
            }
        }
    }
    
    private void loadFeatureIds() {
        try {
            Class<?> globalClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            for (Field field : globalClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                    try {
                        featureNameToIdMap.put(field.getName(), field.getInt(null));
                    } catch (Exception ignored) {}
                }
            }
            
            String[] subClasses = {"Adas", "Instrument", "Setting", "Energy", "Radar"};
            for (String sub : subClasses) {
                try {
                    Class<?> subClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$" + sub);
                    for (Field field : subClass.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                            try {
                                featureNameToIdMap.put(field.getName(), field.getInt(null));
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (ClassNotFoundException ignored) {}
            }
            Log.i(TAG, "Loaded " + featureNameToIdMap.size() + " feature IDs.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load feature IDs", e);
        }
    }

    private void initDevices() {
        VehicleContextWrapper wrappedCtx = new VehicleContextWrapper(this);
        
        // ADAS Device (for ACC and possibly BSD)
        setupDevice(wrappedCtx, "android.hardware.bydauto.adas.BYDAutoADASDevice", 
            "android.hardware.bydauto.adas.AbsBYDAutoADASListener", new AbsBYDAutoADASListener() {
                @Override
                public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
                    processEvent(eventType, eventValue);
                }
            });

        // Instrument Device
        setupDevice(wrappedCtx, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", 
            "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener", new AbsBYDAutoInstrumentListener() {
                @Override
                public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
                    processEvent(eventType, eventValue);
                }
            });

        // Setting Device
        setupDevice(wrappedCtx, "android.hardware.bydauto.setting.BYDAutoSettingDevice", 
            "android.hardware.bydauto.setting.AbsBYDAutoSettingListener", new AbsBYDAutoSettingListener() {
                @Override
                public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
                    processEvent(eventType, eventValue);
                }
            });
            
        // Gearbox Device
        setupDevice(wrappedCtx, "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", 
            "android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener", new AbsBYDAutoGearboxListener() {
                @Override
                public void onCurrentGearChanged(int gear) {
                    handleGearbox(gear);
                }
                @Override
                public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
                    processEvent(eventType, eventValue);
                }
            });
            
        // Energy Device
        setupDevice(wrappedCtx, "android.hardware.bydauto.energy.BYDAutoEnergyDevice", 
            "android.hardware.bydauto.energy.AbsBYDAutoEnergyListener", new AbsBYDAutoEnergyListener() {
                @Override
                public void onOperationModeChanged(int type) {
                    handleOperationMode(type);
                }
                @Override
                public void onEnergyRecycleChanged(int state) {
                    handleEnergyRecycle(state);
                }
                @Override
                public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
                    processEvent(eventType, eventValue);
                }
            });
            
        // Radar Device
        setupDevice(wrappedCtx, "android.hardware.bydauto.radar.BYDAutoRadarDevice", 
            "android.hardware.bydauto.radar.AbsBYDAutoRadarListener", new AbsBYDAutoRadarListener() {
                @Override
                public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
                    processEvent(eventType, eventValue);
                }
            });
            
        Log.i(TAG, "Registered " + devices.size() + " devices in background service.");
    }

    private void setupDevice(VehicleContextWrapper wrappedCtx, String deviceClassName, String listenerClassName, Object listenerObj) {
        try {
            Class<?> clazz = Class.forName(deviceClassName);
            Method getInstanceMethod = clazz.getMethod("getInstance", android.content.Context.class);
            Object device = getInstanceMethod.invoke(null, wrappedCtx);

            if (device != null) {
                Method registerMethod = clazz.getMethod("registerListener", Class.forName(listenerClassName));
                registerMethod.invoke(device, listenerObj);
                
                devices.add(device);
                listeners.add(listenerObj);
                Log.i(TAG, "Registered listener for " + deviceClassName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup device: " + deviceClassName, e);
        }
    }

    private void processEvent(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) {
        if (eventValue != null) {
            int value = -1;
            try {
                Field intValueField = eventValue.getClass().getField("intValue");
                value = intValueField.getInt(eventValue);
            } catch (Exception e) {
                // Ignore silent failures for fast streams
            }
            if (value != -1) {
                // Suppress very frequent event type 315621436 (0x12d0003c) to reduce log clutter
                if (eventType != 315621436) {
                    Log.d(TAG, "processEvent: type=" + eventType + " (0x" + Integer.toHexString(eventType) + "), value=" + value);
                }
                handleDeviceEventLogic(eventType, value);
            }
        }
    }
    
    private void handleDeviceEventLogic(int eventType, int value) {
        // ACC
        Integer accId = featureNameToIdMap.get("ADAS_ACC_MODE");
        if (accId != null && eventType == accId) {
            handleAccMode(value);
        }
        
        // Energy Road Surface (0x24000033 = 603979827)
        if (eventType == 0x24000033) {
            handleEnergyRoadSurface(value);
        }
        
        // Radar (0x26700038 = 644874296)
        if (eventType == 0x26700038) {
            handleRadar(value);
        }
        
        // BSD (0x41800008 = 1098907656) or ID check
        Integer bsdId = featureNameToIdMap.get("ADAS_BLIND_SPOT_ASSIST");
        if ((bsdId != null && eventType == bsdId) || eventType == 0x41800008) {
            handleBsd(value);
        }
        
        // In case Energy Recycle comes as event type (checking both ENERGY_RECYCLE and SET_DR_ENERGY_FB)
        Integer recycleId = featureNameToIdMap.get("ENERGY_RECYCLE");
        Integer energyFbId = featureNameToIdMap.get("SET_DR_ENERGY_FB");
        if ((recycleId != null && eventType == recycleId) || (energyFbId != null && eventType == energyFbId)) {
            handleEnergyRecycle(value);
        }
    }

    private void handleGearbox(int gear) {
        if (gear != lastGearValue) {
            lastGearValue = gear;
            switch (gear) {
                case 0: playRawAudio(R.raw.gear_0_n); break;
                case 1: playRawAudio(R.raw.gear_1_r); break;
                case 2: playRawAudio(R.raw.gear_2_d); break;
                case 3: playRawAudio(R.raw.gear_3_p); break;
            }
            
            Intent intent = new Intent("com.bydaudiomod.GEAR_UPDATE");
            intent.putExtra("value", String.valueOf(gear));
            sendBroadcast(intent);
        }
    }

    private void handleOperationMode(int type) {
        if (type != lastOperationMode) {
            lastOperationMode = type;
            if (type == 1) {
                playRawAudio(R.raw.operation_mode_1_sports_mode_off);
            } else if (type == 2) {
                playRawAudio(R.raw.operation_mode_2_sports_mode_on);
            }
        }
    }

    private void handleEnergyRecycle(int state) {
        if (state != lastEnergyRecycleValue) {
            lastEnergyRecycleValue = state;
            if (state == 2) {
                playRawAudio(R.raw.energy_recycle_2_standard);
            } else if (state == 3) {
                playRawAudio(R.raw.energy_recycle_3_high);
            }
        }
    }

    private void handleEnergyRoadSurface(int value) {
        if (value != lastEnergyValue) {
            lastEnergyValue = value;
            if (value == 1) {
                playRawAudio(R.raw.energy_road_surface_1_common);
            } else if (value == 2) {
                playRawAudio(R.raw.energy_road_surface_2_snow);
            }
        }
    }

    private void handleRadar(int value) {
        if (value != lastRadarValue) {
            lastRadarValue = value;
            if (value == 1) {
                playRawAudio(R.raw.reverse_radar_1_on);
            } else if (value == 0) {
                playRawAudio(R.raw.reverse_radar_0_off);
            }
        }
    }

    private void handleBsd(int value) {
        if (value != lastBsdValue) {
            if (value == 0) {
                playRawAudio(R.raw.adas_blind_spot_assist_0_off);
            } else if (value == 1) {
                if (lastBsdValue == 0) {
                    playRawAudio(R.raw.adas_blind_spot_assist_1_on);
                }
            }
            lastBsdValue = value;
            
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_KEY_BSD_STATE, String.valueOf(value)).apply();
        }
    }

    private void handleAccMode(int value) {
        if (value != lastAccValue) {
            lastAccValue = value;
            
            if (value == 3) {
                if (!isAccActive) {
                    playRawAudio(R.raw.cruise_control_on);
                    isAccActive = true;
                }
            } else if (value == 2 || value == 4) {
                if (isAccActive) {
                    playRawAudio(R.raw.cruise_control_off);
                    isAccActive = false;
                }
            } else if (value == 0 || value == 1) {
                if (isAccActive) {
                    isAccActive = false;
                    playRawAudio(R.raw.cruise_control_off);
                }
                isAccActive = false;
            }
        }
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "BYD Auto Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        Log.d(TAG, "onDestroy: Stopping BydAutoService");
        for (int i = 0; i < devices.size(); i++) {
            Object device = devices.get(i);
            Object listener = listeners.get(i);
            try {
                Class<?> clazz = device.getClass();
                Method[] methods = clazz.getMethods();
                for (Method m : methods) {
                    if (m.getName().equals("unregisterListener") && m.getParameterCount() == 1) {
                        m.invoke(device, listener);
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister listener", e);
            }
        }
        devices.clear();
        listeners.clear();
        
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundMap.clear();

        if (abandonFocusRunnable != null) {
            audioFocusHandler.removeCallbacks(abandonFocusRunnable);
        }
        abandonAudioFocus();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
}
