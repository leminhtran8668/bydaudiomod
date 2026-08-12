package com.bydaudiomod;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * ContextWrapper that overrides all Android permission check methods to
 * unconditionally return PERMISSION_GRANTED.
 */
public class VehicleContextWrapper extends ContextWrapper {

    public VehicleContextWrapper(Context base) {
        super(base);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkSelfPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        // no-op
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        // no-op
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        // no-op
    }
}
