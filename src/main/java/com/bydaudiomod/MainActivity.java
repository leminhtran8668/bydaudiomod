package com.bydaudiomod;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int REQ_DOOR_OPEN = 1;
    private static final int REQ_DOOR_CLOSE = 2;
    private static final int REQ_LOCK = 3;
    private static final int REQ_UNLOCK = 4;
    private static final int REQ_PERMISSION = 100;

    private View contentInside, contentOutside, contentFeedback, contentAbout, masterToggleRow;
    private TextView tabInside, tabOutside, tabFeedback, tabAbout;
    private TextView textServiceStatus, textLastEvent;
    private View statusDot;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private AvasPlayer avasPlayer;
    private int currentTab = 0; // 0=inside, 1=outside, 2=feedback, 3=about

    private CheckBox cbGear, cbAcc, cbBsd, cbRadar, cbEnergyRecycle, cbEnergyRoad, cbOpMode;

    private static final String[][] INSIDE_EVENTS = {
        {DoorSoundService.KEY_DOOR_OPEN_ENABLED, DoorSoundService.KEY_DOOR_OPEN_PATH,
         DoorSoundService.KEY_DOOR_OPEN_VOLUME, "Mở Cửa"},
        {DoorSoundService.KEY_DOOR_CLOSE_ENABLED, DoorSoundService.KEY_DOOR_CLOSE_PATH,
         DoorSoundService.KEY_DOOR_CLOSE_VOLUME, "Đóng Cửa"},
        {DoorSoundService.KEY_LOCK_ENABLED, DoorSoundService.KEY_LOCK_PATH,
         DoorSoundService.KEY_LOCK_VOLUME, "Khoá Xe"},
        {DoorSoundService.KEY_UNLOCK_ENABLED, DoorSoundService.KEY_UNLOCK_PATH,
         DoorSoundService.KEY_UNLOCK_VOLUME, "Mở Khoá"},
    };

    private static final int[] INSIDE_CARD_IDS = {
        R.id.card_inside_door_open, R.id.card_inside_door_close,
        R.id.card_inside_lock, R.id.card_inside_unlock,
    };

    private static final int[] INSIDE_REQ_CODES = {
        REQ_DOOR_OPEN, REQ_DOOR_CLOSE, REQ_LOCK, REQ_UNLOCK,
    };

    private static final String[][] OUTSIDE_EVENTS = {
        {DoorSoundService.KEY_OUTSIDE_DOOR_OPEN_ENABLED,
         DoorSoundService.KEY_OUTSIDE_DOOR_OPEN_PATTERN, "Mở Cửa"},
        {DoorSoundService.KEY_OUTSIDE_DOOR_CLOSE_ENABLED,
         DoorSoundService.KEY_OUTSIDE_DOOR_CLOSE_PATTERN, "Đóng Cửa"},
        {DoorSoundService.KEY_OUTSIDE_LOCK_ENABLED,
         DoorSoundService.KEY_OUTSIDE_LOCK_PATTERN, "Khoá Xe"},
        {DoorSoundService.KEY_OUTSIDE_UNLOCK_ENABLED,
         DoorSoundService.KEY_OUTSIDE_UNLOCK_PATTERN, "Mở Khoá"},
    };

    private static final int[] OUTSIDE_CARD_IDS = {
        R.id.card_outside_door_open, R.id.card_outside_door_close,
        R.id.card_outside_lock, R.id.card_outside_unlock,
    };

    private TextView[] insideFileLabels = new TextView[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(0xFF0A0A0F);
        getWindow().setNavigationBarColor(0xFF0A0A0F);

        SharedPreferences prefs = getSharedPreferences(DoorSoundService.PREF_NAME, MODE_PRIVATE);
        int maxVolume = ((AudioManager) getSystemService(AUDIO_SERVICE))
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        avasPlayer = new AvasPlayer(new BydPermissionContext(this));

        // Tabs
        tabInside = findViewById(R.id.tab_inside);
        tabOutside = findViewById(R.id.tab_outside);
        tabFeedback = findViewById(R.id.tab_feedback);
        tabAbout = findViewById(R.id.tab_about);
        contentInside = findViewById(R.id.content_inside);
        contentOutside = findViewById(R.id.content_outside);
        contentFeedback = findViewById(R.id.content_feedback);
        contentAbout = findViewById(R.id.content_about);
        masterToggleRow = findViewById(R.id.master_toggle_row);

        tabInside.setOnClickListener(v -> switchTab(0));
        tabOutside.setOnClickListener(v -> switchTab(1));
        tabFeedback.setOnClickListener(v -> switchTab(2));
        tabAbout.setOnClickListener(v -> switchTab(3));

        // Master toggle (door service)
        Switch switchEnabled = findViewById(R.id.switch_enabled);
        switchEnabled.setChecked(prefs.getBoolean(DoorSoundService.KEY_ENABLED, false));
        switchEnabled.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(DoorSoundService.KEY_ENABLED, checked).apply();
            if (checked) {
                startService(new Intent(this, DoorSoundService.class));
            } else {
                stopService(new Intent(this, DoorSoundService.class));
            }
            updateStatus();
        });

        // Inside speaker cards
        for (int i = 0; i < 4; i++) {
            View card = findViewById(INSIDE_CARD_IDS[i]);
            setupInsideCard(card, INSIDE_EVENTS[i], INSIDE_REQ_CODES[i], maxVolume, prefs);
            insideFileLabels[i] = card.findViewById(R.id.card_file_label);
        }

        // Outside speaker cards
        for (int i = 0; i < 4; i++) {
            View card = findViewById(OUTSIDE_CARD_IDS[i]);
            setupOutsideCard(card, OUTSIDE_EVENTS[i], prefs);
        }

        // Feedback checkboxes
        SharedPreferences fbPrefs = getSharedPreferences(ConfigData.PREF_NAME, MODE_PRIVATE);
        cbGear = findViewById(R.id.cb_gear_sound);
        cbAcc = findViewById(R.id.cb_acc_sound);
        cbBsd = findViewById(R.id.cb_bsd_sound);
        cbRadar = findViewById(R.id.cb_radar_sound);
        cbEnergyRecycle = findViewById(R.id.cb_energy_recycle_sound);
        cbEnergyRoad = findViewById(R.id.cb_energy_road_sound);
        cbOpMode = findViewById(R.id.cb_operation_mode_sound);

        cbGear.setChecked(fbPrefs.getBoolean(ConfigData.KEY_GEAR_SOUND, true));
        cbAcc.setChecked(fbPrefs.getBoolean(ConfigData.KEY_ACC_SOUND, true));
        cbBsd.setChecked(fbPrefs.getBoolean(ConfigData.KEY_BSD_SOUND, true));
        cbRadar.setChecked(fbPrefs.getBoolean(ConfigData.KEY_RADAR_SOUND, true));
        cbEnergyRecycle.setChecked(fbPrefs.getBoolean(ConfigData.KEY_ENERGY_RECYCLE_SOUND, true));
        cbEnergyRoad.setChecked(fbPrefs.getBoolean(ConfigData.KEY_ENERGY_ROAD_SOUND, true));
        cbOpMode.setChecked(fbPrefs.getBoolean(ConfigData.KEY_OPERATION_MODE_SOUND, true));

        findViewById(R.id.btn_save_feedback).setOnClickListener(v -> {
            fbPrefs.edit()
                    .putBoolean(ConfigData.KEY_GEAR_SOUND, cbGear.isChecked())
                    .putBoolean(ConfigData.KEY_ACC_SOUND, cbAcc.isChecked())
                    .putBoolean(ConfigData.KEY_BSD_SOUND, cbBsd.isChecked())
                    .putBoolean(ConfigData.KEY_RADAR_SOUND, cbRadar.isChecked())
                    .putBoolean(ConfigData.KEY_ENERGY_RECYCLE_SOUND, cbEnergyRecycle.isChecked())
                    .putBoolean(ConfigData.KEY_ENERGY_ROAD_SOUND, cbEnergyRoad.isChecked())
                    .putBoolean(ConfigData.KEY_OPERATION_MODE_SOUND, cbOpMode.isChecked())
                    .apply();
            // Restart feedback service to apply
            stopService(new Intent(this, BydAutoService.class));
            startService(new Intent(this, BydAutoService.class));
            android.widget.Toast.makeText(this, "Đã lưu cài đặt phản hồi", android.widget.Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        // About / Dev tools
        findViewById(R.id.btn_adas).setOnClickListener(v ->
                startActivity(new Intent(this, AdasDashboardActivity.class)));
        findViewById(R.id.btn_reflection).setOnClickListener(v ->
                startActivity(new Intent(this, ReflectionTestActivity.class)));

        // Status
        textServiceStatus = findViewById(R.id.text_service_status);
        textLastEvent = findViewById(R.id.text_last_event);
        statusDot = findViewById(R.id.status_dot);

        requestStoragePermission();
        updateFileLabels();
        updateStatus();

        // Start feedback service by default
        startService(new Intent(this, BydAutoService.class));

        refreshHandler = new Handler();
        refreshRunnable = new StatusRefresher(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFileLabels();
        updateStatus();
        refreshHandler.postDelayed(refreshRunnable, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (avasPlayer != null) avasPlayer.stop();
    }

    private void switchTab(int tab) {
        currentTab = tab;
        contentInside.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        contentOutside.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        contentFeedback.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        contentAbout.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        // Master toggle only for door tabs
        if (masterToggleRow != null) {
            masterToggleRow.setVisibility((tab == 0 || tab == 1) ? View.VISIBLE : View.GONE);
        }

        TextView[] tabs = {tabInside, tabOutside, tabFeedback, tabAbout};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == tab);
            tabs[i].setBackgroundResource(active ? R.drawable.tab_active : android.R.color.transparent);
            tabs[i].setTextColor(active ? 0xFFFFFFFF : 0xFF9E9E9E);
        }
    }

    private void setupInsideCard(View card, String[] keys, int reqCode,
                                  int maxVolume, SharedPreferences prefs) {
        String enableKey = keys[0], pathKey = keys[1], volKey = keys[2], title = keys[3];

        ((TextView) card.findViewById(R.id.card_title)).setText(title);

        Switch sw = card.findViewById(R.id.card_switch);
        sw.setChecked(prefs.getBoolean(enableKey, true));
        sw.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(enableKey, checked).apply());

        SeekBar seekBar = card.findViewById(R.id.card_seekbar);
        TextView volLabel = card.findViewById(R.id.card_volume_label);
        seekBar.setMax(maxVolume);
        int current = prefs.getInt(volKey, DoorSoundService.DEFAULT_VOLUME);
        seekBar.setProgress(current);
        volLabel.setText(String.valueOf(current));
        seekBar.setOnSeekBarChangeListener(new VolumeChangeListener(volLabel, volKey, prefs));

        card.findViewById(R.id.card_btn_select).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, getString(R.string.select_file_chooser_title)), reqCode);
        });

        TextView fileLabel = card.findViewById(R.id.card_file_label);
        card.findViewById(R.id.card_btn_clear).setOnClickListener(v -> {
            prefs.edit().remove(pathKey).apply();
            fileLabel.setText(R.string.no_file_selected);
        });
    }

    private void setupOutsideCard(View card, String[] keys, SharedPreferences prefs) {
        String enableKey = keys[0], patternKey = keys[1], title = keys[2];

        ((TextView) card.findViewById(R.id.card_title)).setText(title);

        Switch sw = card.findViewById(R.id.card_switch);
        sw.setChecked(prefs.getBoolean(enableKey, false));
        sw.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(enableKey, checked).apply());

        Spinner spinner = card.findViewById(R.id.card_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.pattern_names, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);

        int savedPattern = prefs.getInt(patternKey, AvasPlayer.PATTERN_NONE);
        spinner.setSelection(savedPattern + 1);
        spinner.setOnItemSelectedListener(new PatternSelectListener(prefs, patternKey));

        Button preview = card.findViewById(R.id.card_btn_preview);
        preview.setOnClickListener(v -> {
            int pos = spinner.getSelectedItemPosition();
            int pattern = pos - 1;
            if (pattern >= 0 && avasPlayer != null) {
                avasPlayer.play(pattern);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String pathKey = null;
        switch (requestCode) {
            case REQ_DOOR_OPEN:  pathKey = DoorSoundService.KEY_DOOR_OPEN_PATH; break;
            case REQ_DOOR_CLOSE: pathKey = DoorSoundService.KEY_DOOR_CLOSE_PATH; break;
            case REQ_LOCK:       pathKey = DoorSoundService.KEY_LOCK_PATH; break;
            case REQ_UNLOCK:     pathKey = DoorSoundService.KEY_UNLOCK_PATH; break;
            default: return;
        }

        String localPath = copyToLocal(uri, pathKey);
        if (localPath != null) {
            getSharedPreferences(DoorSoundService.PREF_NAME, MODE_PRIVATE)
                    .edit().putString(pathKey, localPath).apply();
        }
        updateFileLabels();
    }

    private String copyToLocal(Uri uri, String key) {
        try {
            File dir = new File(getFilesDir(), "sounds");
            dir.mkdirs();
            String filename = getFileName(uri);
            if (filename == null) filename = key + ".audio";
            File dest = new File(dir, filename);
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.close();
            in.close();
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private String getFileName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
                c.close();
            }
        }
        if (name == null) name = uri.getLastPathSegment();
        return name;
    }

    private void updateFileLabels() {
        SharedPreferences prefs = getSharedPreferences(DoorSoundService.PREF_NAME, MODE_PRIVATE);
        String[] pathKeys = {
            DoorSoundService.KEY_DOOR_OPEN_PATH, DoorSoundService.KEY_DOOR_CLOSE_PATH,
            DoorSoundService.KEY_LOCK_PATH, DoorSoundService.KEY_UNLOCK_PATH,
        };
        for (int i = 0; i < 4; i++) {
            String path = prefs.getString(pathKeys[i], null);
            if (path == null || path.isEmpty()) {
                insideFileLabels[i].setText(R.string.no_file_selected);
            } else {
                insideFileLabels[i].setText(new File(path).getName());
            }
        }
    }

    void updateStatus() {
        boolean doorRunning = DoorSoundService.isRunning();
        boolean fbRunning = BydAutoService.isRunning;
        boolean any = doorRunning || fbRunning;
        String status = "Cửa: " + (doorRunning ? "ON" : "OFF")
                + "  |  Phản hồi: " + (fbRunning ? "ON" : "OFF");
        textServiceStatus.setText(status);
        textServiceStatus.setTextColor(any ? 0xFF4CAF50 : 0xFFFF5252);
        statusDot.setBackgroundResource(any
                ? R.drawable.status_dot_running : R.drawable.status_dot_stopped);

        SharedPreferences prefs = getSharedPreferences(DoorSoundService.PREF_NAME, MODE_PRIVATE);
        String lastEvent = prefs.getString(DoorSoundService.KEY_LAST_EVENT, null);
        textLastEvent.setText(lastEvent != null ? lastEvent : getString(R.string.no_events_yet));
    }

    private void requestStoragePermission() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
        }
    }
}
