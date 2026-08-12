package com.bydaudiomod;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class ReflectionTestActivity extends Activity {

    private static final String TAG = "ReflectionTest";
    private TextView tvResult;
    private Button btnScanServices;
    private Button btnTestReflection;
    
    private boolean isAutoRefresh = false;
    private android.os.Handler refreshHandler = new android.os.Handler();
    private List<DeviceInstance> cachedDevices = new ArrayList<>();

    private static class DeviceInstance {
        String name;
        Object instance;
        List<Method> getters = new ArrayList<>();

        DeviceInstance(String name, Object instance) {
            this.name = name;
            this.instance = instance;
            // Pre-scan for all "get" methods that take 0 parameters and return primitive-ish types
            for (Method m : instance.getClass().getMethods()) {
                if (m.getName().startsWith("get") && m.getParameterCount() == 0) {
                    Class<?> ret = m.getReturnType();
                    if (ret.isPrimitive() || ret == String.class || Number.class.isAssignableFrom(ret) || ret == Boolean.class) {
                        getters.add(m);
                    }
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reflection_test);

        tvResult = findViewById(R.id.tv_reflection_result);
        btnScanServices = findViewById(R.id.btn_scan_services);
        btnTestReflection = findViewById(R.id.btn_test_reflection);
        btnTestReflection.setText("Bắt đầu tự động làm mới");

        exemptHiddenApis();

        btnScanServices.setOnClickListener(v -> scanBydServices());
        btnTestReflection.setOnClickListener(v -> toggleAutoRefresh());
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoRefresh();
    }

    private void toggleAutoRefresh() {
        if (isAutoRefresh) {
            stopAutoRefresh();
        } else {
            startAutoRefresh();
        }
    }

    private void startAutoRefresh() {
        isAutoRefresh = true;
        btnTestReflection.setText("Dừng tự động làm mới");
        if (cachedDevices.isEmpty()) {
            initDeviceInstances();
        }
        refreshHandler.post(refreshRunnable);
    }

    private void stopAutoRefresh() {
        isAutoRefresh = false;
        btnTestReflection.setText("Bắt đầu tự động làm mới");
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    private Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAutoRefresh) return;
            updateDashboard();
            refreshHandler.postDelayed(this, 1000);
        }
    };

    private void initDeviceInstances() {
        Log.d(TAG, "Initializing Device Instances...");
        VehicleContextWrapper wrappedCtx = new VehicleContextWrapper(this);
        String[] targetClasses = {
            "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
            "android.hardware.bydauto.power.BYDAutoPowerDevice",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "android.hardware.bydauto.engine.BYDAutoEngineDevice",
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.signal.BYDAutoSignalDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.location.BYDAutoLocationDevice",
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "android.hardware.bydauto.motor.BYDAutoMotorDevice",
            "android.hardware.bydauto.radar.BYDAutoRadarDevice",
            "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
            "android.hardware.bydauto.adas.BYDAutoADASDevice"
        };

        for (String className : targetClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                Method getInstanceMethod = null;
                try {
                    getInstanceMethod = clazz.getMethod("getInstance", android.content.Context.class);
                } catch (NoSuchMethodException e) {
                    try { getInstanceMethod = clazz.getMethod("getInstance"); } catch (Exception ignored) {}
                }

                if (getInstanceMethod != null) {
                    Object instance = (getInstanceMethod.getParameterCount() == 1) ? 
                        getInstanceMethod.invoke(null, wrappedCtx) : getInstanceMethod.invoke(null);
                    
                    if (instance != null) {
                        String simpleName = className.substring(className.lastIndexOf('.') + 1);
                        cachedDevices.add(new DeviceInstance(simpleName, instance));
                        Log.i(TAG, "Cached: " + simpleName);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to init " + className + ": " + e.getMessage());
            }
        }
    }

    private void updateDashboard() {
        StringBuilder sb = new StringBuilder("=== BYD Real-time Dashboard ===\n");
        sb.append("Cập nhật lúc: ").append(new java.util.Date().toString()).append("\n\n");

        for (DeviceInstance dev : cachedDevices) {
            sb.append("▶ ").append(dev.name).append("\n");
            int valuesFound = 0;
            for (Method m : dev.getters) {
                try {
                    Object val = m.invoke(dev.instance);
                    if (val != null) {
                        sb.append("  ").append(m.getName().substring(3)).append(": ").append(val).append("\n");
                        valuesFound++;
                    }
                } catch (Exception ignored) {}
            }
            if (valuesFound == 0) sb.append("  (Không có dữ liệu)\n");
            sb.append("\n");
        }
        tvResult.setText(sb.toString());
    }

    private void exemptHiddenApis() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});

            Object sVmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(sVmRuntime, new Object[]{new String[]{"L"}});
            Log.d(TAG, "Hidden API exemption successful");
        } catch (Exception e) {
            Log.e(TAG, "Hidden API exemption failed", e);
        }
    }

    private void scanBydServices() {
        Log.d(TAG, "Starting scanBydServices...");
        StringBuilder sb = new StringBuilder("Scanning for BYD services...\n\n");
        
        // List system libraries
        try {
            sb.append("Thư viện dùng chung của hệ thống:\n");
            String[] libs = getPackageManager().getSystemSharedLibraryNames();
            if (libs != null) {
                for (String lib : libs) {
                    if (lib.contains("car") || lib.contains("byd") || lib.contains("auto")) {
                        sb.append("  - ").append(lib).append("\n");
                        Log.i(TAG, "Found System Library: " + lib);
                    }
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            Log.e(TAG, "Error listing libraries", e);
        }

        // List framework jars
        try {
            sb.append("Framework JAR (thư viện tiềm năng):\n");
            java.io.File frameworkDir = new java.io.File("/system/framework/");
            if (frameworkDir.exists() && frameworkDir.isDirectory()) {
                java.io.File[] files = frameworkDir.listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        String name = file.getName();
                        if (name.endsWith(".jar") && (name.contains("byd") || name.contains("car") || name.contains("auto"))) {
                            sb.append("  - ").append(name).append("\n");
                            Log.d(TAG, "Found Jar: " + name);
                        }
                    }
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            Log.e(TAG, "Error listing framework jars", e);
        }

        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method listServicesMethod = serviceManagerClass.getMethod("listServices");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            String[] services = (String[]) listServicesMethod.invoke(null);

            if (services != null) {
                Log.d(TAG, "Found " + services.length + " services in total.");
                sb.append("Dịch vụ tiềm năng & Descriptor:\n");
                for (String service : services) {
                    if (service.toLowerCase().contains("byd") || 
                        service.toLowerCase().contains("car") || 
                        service.toLowerCase().contains("auto") ||
                        service.toLowerCase().contains("vehicle")) {
                        
                        Log.d(TAG, "Checking service: " + service);
                        String descriptor = "Unknown";
                        try {
                            IBinder binder = (IBinder) getServiceMethod.invoke(null, service);
                            if (binder != null) {
                                descriptor = binder.getInterfaceDescriptor();
                                // Try asInterface if we suspect a class
                                tryAsInterface(service, descriptor, binder, sb);
                            }
                        } catch (Exception e) {
                            descriptor = "Error: " + e.getMessage();
                        }
                        
                        sb.append("- ").append(service).append("\n");
                        sb.append("  Descriptor: ").append(descriptor).append("\n");
                        Log.i(TAG, "Service: " + service + " | Descriptor: " + descriptor);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scanning services", e);
            sb.append("Lỗi khi quét dịch vụ: ").append(e.getMessage()).append("\n");
        }
        tvResult.setText(sb.toString());
    }

    private void tryAsInterface(String serviceName, String descriptor, IBinder binder, StringBuilder sb) {
        if (descriptor == null || descriptor.isEmpty()) return;
        try {
            String stubClassName = descriptor + "$Stub";
            Class<?> stubClass = Class.forName(stubClassName);
            Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
            Object serviceInterface = asInterfaceMethod.invoke(null, binder);
            if (serviceInterface != null) {
                Log.i(TAG, "✅ asInterface success for: " + descriptor);
                sb.append("  ✅ asInterface THÀNH CÔNG!\n");
                
                // List methods of the interface
                Method[] methods = serviceInterface.getClass().getMethods();
                Log.d(TAG, "Interface " + descriptor + " has " + methods.length + " methods.");
                for (Method m : methods) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    Log.v(TAG, "  Method: " + m.getName());
                }
            }
        } catch (Exception ignored) {
            // Log.v(TAG, "asInterface failed for " + descriptor);
        }
    }

    private void invokeAndAppend(Object instance, String methodName, StringBuilder sb) {
        try {
            Method m = instance.getClass().getMethod(methodName);
            Object result = m.invoke(instance);
            sb.append("    ⭐ ").append(methodName).append("(): ").append(result).append("\n");
            Log.i(TAG, "Data extracted: " + methodName + " = " + result);
        } catch (NoSuchMethodException e) {
            // Quietly ignore missing methods for this specific device
        } catch (Exception e) {
            Log.w(TAG, "Failed to invoke " + methodName + ": " + e.getMessage());
        }
    }

    private void testCarDataManagerReflection() {
        Log.d(TAG, "Starting testCarDataManagerReflection...");
        StringBuilder sb = new StringBuilder("Testing BYD Hardware Devices Reflection...\n\n");
        
        VehicleContextWrapper wrappedCtx = new VehicleContextWrapper(this);

        String[] targetClasses = {
            "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
            "android.hardware.bydauto.power.BYDAutoPowerDevice",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "android.hardware.bydauto.mqtt.BYDAutoMqttDevice",
            "android.hardware.bydauto.engine.BYDAutoEngineDevice",
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.battery.BYDAutoBatteryDevice",
            "android.hardware.bydauto.signal.BYDAutoSignalDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.location.BYDAutoLocationDevice",
            "android.hardware.bydauto.ota.BYDAutoOtaDevice",
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "android.hardware.bydauto.motor.BYDAutoMotorDevice",
            "android.hardware.bydauto.radar.BYDAutoRadarDevice",
            "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
            "com.byd.vem.VehicleEnergyModelManager",
            "android.gui.BYDAutoServer"
        };

        for (String className : targetClasses) {
            Log.d(TAG, "Attempting to load class: " + className);
            sb.append("Đang kiểm tra: ").append(className).append("\n");
            try {
                Class<?> clazz = Class.forName(className);
                Log.i(TAG, "✅ Successfully loaded class: " + className);
                sb.append("  ✅ Đã tìm thấy lớp!\n");
                
                Method[] methods = clazz.getDeclaredMethods();
                Log.d(TAG, "Found " + methods.length + " methods in " + className);
                sb.append("  Tìm thấy ").append(methods.length).append(" phương thức.\n");
                
                // Try to get instance with wrapped context
                try {
                    Method getInstanceMethod = null;
                    try {
                        getInstanceMethod = clazz.getMethod("getInstance", android.content.Context.class);
                    } catch (NoSuchMethodException e) {
                        try {
                            getInstanceMethod = clazz.getMethod("getInstance");
                        } catch (NoSuchMethodException e2) {}
                    }

                    if (getInstanceMethod != null) {
                        Object instance;
                        if (getInstanceMethod.getParameterCount() == 1) {
                            instance = getInstanceMethod.invoke(null, wrappedCtx);
                        } else {
                            instance = getInstanceMethod.invoke(null);
                        }
                        
                        if (instance != null) {
                            Log.i(TAG, "✅ Instance obtained for " + className);
                            sb.append("  ✅ Đã lấy được instance!\n");
                            
                            // Targeted data extraction based on class name
                            try {
                                if (className.contains("Gearbox")) {
                                    invokeAndAppend(instance, "getCurrentGear", sb);
                                } else if (className.contains("Speed")) {
                                    invokeAndAppend(instance, "getVehicleSpeed", sb);
                                } else if (className.contains("Charging")) {
                                    invokeAndAppend(instance, "getChargingPower", sb);
                                    invokeAndAppend(instance, "getChargingState", sb);
                                } else if (className.contains("Instrument")) {
                                    invokeAndAppend(instance, "getOdometer", sb);
                                    invokeAndAppend(instance, "getAverageSpeed", sb);
                                } else if (className.contains("Battery")) {
                                    invokeAndAppend(instance, "getBatteryTotalVoltage", sb);
                                    invokeAndAppend(instance, "getBatterySoh", sb);
                                } else if (className.contains("Energy")) {
                                    invokeAndAppend(instance, "getEnergyMode", sb);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Extraction failed for " + className + ": " + e.getMessage());
                            }

                            // List first 5 instance methods
                            Method[] instMethods = instance.getClass().getMethods();
                            int mCount = 0;
                            for (Method m : instMethods) {
                                if (m.getDeclaringClass() == Object.class) continue;
                                if (mCount++ >= 5) break;
                                sb.append("    - Method: ").append(m.getName()).append("\n");
                            }
                        } else {
                            sb.append("  ❌ Instance rỗng (null).\n");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting instance for " + className, e);
                    sb.append("  ❌ Lỗi instance: ").append(e.getMessage()).append("\n");
                }

                // List first 5 static methods/fields
                int count = 0;
                for (Method method : methods) {
                    if (count++ >= 5) break;
                    sb.append("  - ").append(method.getName()).append("\n");
                }

            } catch (ClassNotFoundException e) {
                Log.w(TAG, "❌ Class not found: " + className);
                sb.append("  ❌ Không tìm thấy lớp.\n");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error loading class: " + className, e);
                sb.append("  ❌ Lỗi: ").append(e.getMessage()).append("\n");
            }
            sb.append("\n");
        }
        tvResult.setText(sb.toString());
    }
}
