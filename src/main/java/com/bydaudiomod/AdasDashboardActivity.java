package com.bydaudiomod;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;
import android.widget.TextView;

import android.hardware.IBYDAutoEvent;
import android.hardware.bydauto.adas.AbsBYDAutoADASListener;
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener;
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener;
import android.hardware.bydauto.radar.AbsBYDAutoRadarListener;
import android.hardware.bydauto.panorama.AbsBYDAutoPanoramaListener;
import android.hardware.bydauto.light.AbsBYDAutoLightListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdasDashboardActivity extends Activity {

    private static final String TAG = "AdasDashboard";
    private TextView tvStatus;
    private TextView tvLeftCol;
    private TextView tvMidCol;
    private TextView tvRightCol;
    
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private List<Object> devices = new ArrayList<>();
    private List<Object> listeners = new ArrayList<>();
    
    private Map<Integer, String> featureIdToNameMap = new HashMap<>();
    private Map<Integer, Integer> currentValues = new HashMap<>();
    private Map<Integer, Long> lastChangeTime = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_adas_dashboard);

        tvStatus = findViewById(R.id.tv_adas_status);
        tvLeftCol = findViewById(R.id.tv_left_col);
        tvMidCol = findViewById(R.id.tv_mid_col);
        tvRightCol = findViewById(R.id.tv_right_col);

        loadFeatureIds();
        initDevices();
    }

    private void loadFeatureIds() {
        try {
            Class<?> globalClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            for (Field field : globalClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                    try { featureIdToNameMap.put(field.getInt(null), field.getName()); } catch (Exception ignored) {}
                }
            }
            
            String[] subClasses = {"Adas", "Instrument", "Setting", "Radar", "Panorama", "Light", "Bodywork"};
            for (String sub : subClasses) {
                try {
                    Class<?> subClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$" + sub);
                    for (Field field : subClass.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                            try { featureIdToNameMap.put(field.getInt(null), field.getName()); } catch (Exception ignored) {}
                        }
                    }
                } catch (ClassNotFoundException ignored) {}
            }
            Log.i(TAG, "Loaded " + featureIdToNameMap.size() + " feature IDs.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load feature IDs", e);
        }
    }

    private void initDevices() {
        VehicleContextWrapper wrappedCtx = new VehicleContextWrapper(this);
        
        setupDevice(wrappedCtx, "android.hardware.bydauto.adas.BYDAutoADASDevice", 
            "android.hardware.bydauto.adas.AbsBYDAutoADASListener", new AbsBYDAutoADASListener() {
                @Override public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) { processEvent(eventType, eventValue); }
            });

        setupDevice(wrappedCtx, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", 
            "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener", new AbsBYDAutoInstrumentListener() {
                @Override public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) { processEvent(eventType, eventValue); }
            });

        setupDevice(wrappedCtx, "android.hardware.bydauto.setting.BYDAutoSettingDevice", 
            "android.hardware.bydauto.setting.AbsBYDAutoSettingListener", new AbsBYDAutoSettingListener() {
                @Override public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) { processEvent(eventType, eventValue); }
            });

        setupDevice(wrappedCtx, "android.hardware.bydauto.radar.BYDAutoRadarDevice", 
            "android.hardware.bydauto.radar.AbsBYDAutoRadarListener", new AbsBYDAutoRadarListener() {
                @Override public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) { processEvent(eventType, eventValue); }
            });

        setupDevice(wrappedCtx, "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice", 
            "android.hardware.bydauto.panorama.AbsBYDAutoPanoramaListener", new AbsBYDAutoPanoramaListener() {
                @Override public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) { processEvent(eventType, eventValue); }
            });

        setupDevice(wrappedCtx, "android.hardware.bydauto.light.BYDAutoLightDevice", 
            "android.hardware.bydauto.light.AbsBYDAutoLightListener", new AbsBYDAutoLightListener() {
                @Override public void onDataEventChanged(int eventType, android.hardware.bydauto.BYDAutoEventValue eventValue) { processEvent(eventType, eventValue); }
            });

        tvStatus.setText("Đang lắng nghe " + devices.size() + " thiết bị...");
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
            } catch (Exception e) {}
            if (value != -1) {
                handleEvent(eventType, value);
            }
        }
    }

    private void handleEvent(int eventType, int value) {
        long currentTime = System.currentTimeMillis();
        Integer prevValue = currentValues.get(eventType);
        if (prevValue == null || prevValue != value) {
            lastChangeTime.put(eventType, currentTime);
        }
        currentValues.put(eventType, value);
        mainHandler.post(this::updateUI);
    }

    private void updateUI() {
        long currentTime = System.currentTimeMillis();

        // --- Left Column: AVS (Panorama), Radar & Specific BSD ---
        StringBuilder leftSb = new StringBuilder();
        leftSb.append("<b>[AVS (Toàn cảnh), Radar & BSD cụ thể]</b><br/>");
        
        List<String> leftTargets = new ArrayList<>();
        leftTargets.add("INSTRUMENT_B_M_BSD_SYSTEM");
        leftTargets.add("ADAS_BSD_STATE_HAL");
        
        for (Map.Entry<Integer, Integer> entry : currentValues.entrySet()) {
            String name = featureIdToNameMap.get(entry.getKey());
            if (name != null) {
                if (name.startsWith("PANORAMA_") || name.startsWith("RADAR_")) {
                    if (!leftTargets.contains(name)) leftTargets.add(name);
                }
            }
        }
        
        for (String target : leftTargets) {
            appendValueIfPresent(leftSb, target, currentTime, true);
        }
        tvLeftCol.setText(Html.fromHtml(leftSb.toString(), Html.FROM_HTML_MODE_COMPACT));

        // --- Middle Column: Turn Indicators & BSD Suspects ---
        StringBuilder midSb = new StringBuilder();
        midSb.append("<b>[Đèn xi-nhan, BSD & Khoảng cách ACC]</b><br/>");
        
        List<String> midTargets = new ArrayList<>();
        midTargets.add("LIGHT_TURN_SIGNAL_LIGHT");
        midTargets.add("ADAS_BSD_STATE");
        midTargets.add("ADAS_BLIND_SPOT_ASSIST");
        
        // Front distance candidates
        midTargets.add("INSTRUMENT_DISTANCE_OF_TARGET_AHEAD_ADVANCED_SET");
        midTargets.add("INSTRUMENT_2IN1_ACC_DISTANCE");
        midTargets.add("INSTRUMENT_2IN1_ACC_TIME_DISTANCE");
        
        for (Map.Entry<Integer, Integer> entry : currentValues.entrySet()) {
            String name = featureIdToNameMap.get(entry.getKey());
            if (name != null) {
                if (name.contains("TURN_SIGNAL") && !midTargets.contains(name)) {
                    // Skipping other turn signals as requested to use only LIGHT_TURN_SIGNAL_LIGHT
                } else if (name.contains("BSD") && !leftTargets.contains(name) && !midTargets.contains(name)) {
                    midTargets.add(name);
                } else if (name.contains("ACC_DISTANCE") && !midTargets.contains(name)) {
                    midTargets.add(name);
                }
            }
        }

        for (String target : midTargets) {
            appendValueIfPresent(midSb, target, currentTime, false);
        }
        tvMidCol.setText(Html.fromHtml(midSb.toString(), Html.FROM_HTML_MODE_COMPACT));

        // --- Right Column: All Values ---
        StringBuilder rightSb = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : currentValues.entrySet()) {
            int eventType = entry.getKey();
            int value = entry.getValue();
            String name = featureIdToNameMap.get(eventType);
            if (name == null) name = "ID_" + eventType;

            Long lastChange = lastChangeTime.get(eventType);
            boolean isRecent = lastChange != null && (currentTime - lastChange < 3000);

            if (isRecent) {
                rightSb.append("<b><font color='#FFFF00'>▶ ").append(name).append(": ")
                       .append(value).append("</font></b><br/>");
            } else {
                rightSb.append("<font color='#00FF00'>  ").append(name).append(": ")
                       .append(value).append("</font><br/>");
            }
        }
        tvRightCol.setText(Html.fromHtml(rightSb.toString(), Html.FROM_HTML_MODE_COMPACT));

        tvStatus.setText("Thiết bị: " + devices.size() + " | Trường dữ liệu: " + currentValues.size() + " | " + new java.util.Date().toString().substring(11, 19));
    }

    private void appendValueIfPresent(StringBuilder sb, String featureName, long currentTime, boolean guessMeaning) {
        Integer targetId = null;
        for (Map.Entry<Integer, String> entry : featureIdToNameMap.entrySet()) {
            if (entry.getValue().equals(featureName)) {
                targetId = entry.getKey();
                break;
            }
        }

        if (targetId != null && currentValues.containsKey(targetId)) {
            int value = currentValues.get(targetId);
            Long lastChange = lastChangeTime.get(targetId);
            boolean isRecent = lastChange != null && (currentTime - lastChange < 3000);

            String meaning = "";
            if (featureName.equals("LIGHT_TURN_SIGNAL_LIGHT")) {
                if (value == 1) meaning = " (TẮT)";
                else if (value == 2) meaning = " (TRÁI)";
                else if (value == 4) meaning = " (PHẢI)";
                else meaning = " (" + value + ")";
            } else if (guessMeaning || featureName.contains("TURN_SIGNAL") || featureName.contains("BSD")) {
                if (value == 0) meaning = " (TẮT/0)";
                else if (value == 1) meaning = " (BẬT/Chờ/1)";
                else if (value == 2) meaning = " (Hoạt động/Phát hiện/2)";
                else meaning = " (" + value + ")";
            }

            if (isRecent) {
                sb.append("<b><font color='#FFFF00'>").append(featureName).append(": ")
                  .append(value).append(meaning).append("</font></b><br/>");
            } else {
                sb.append(featureName).append(": ").append(value).append(meaning).append("<br/>");
            }
        } else {
            // Optional: Hide N/A to keep the screen clean, or show it so the user knows it's being tracked.
            sb.append("<font color='#555555'>").append(featureName).append(": N/A</font><br/>");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
    }
}