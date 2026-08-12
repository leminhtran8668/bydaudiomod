package android.hardware.bydauto.bodywork;

import android.hardware.IBYDAutoEvent;
import android.hardware.IBYDAutoListener;
import android.hardware.bydauto.BYDAutoEventValue;

public class AbsBYDAutoBodyworkListener implements IBYDAutoListener {
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int eventType, BYDAutoEventValue eventValue) {}
    public void onError(int errCode, String errMessage) {}

    public void onDoorStateChanged(int area, int state) {}
    public void onAutoSystemStateChanged(int state) {}
    public void onPowerLevelChanged(int level) {}
    public void onAlarmStateChanged(int state) {}
    public void onAutoVINChanged(String vin) {}
    public void onBatteryVoltageLevelChanged(int level) {}
    public void onWindowStateChanged(int area, int state) {}
    public void onSunroofStateChanged(int state) {}
    public void onFuelElecLowPowerChanged(int level) {}
    public void onPowerDayModeChanged(int mode) {}
    public void onHasMessageChanged(int type, int state) {}
}
