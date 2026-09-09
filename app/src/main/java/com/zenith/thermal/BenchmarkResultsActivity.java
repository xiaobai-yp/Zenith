package com.zenith.thermal;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BenchmarkResultsActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FpsSample> fpsHistory = new ArrayList<>();

    private boolean running;
    private TextView statusText;
    private TextView durationText;
    private TextView avgFpsText, minFpsText, maxFpsText;
    private TextView avgTempText, battDrainText;
    private TextView startStopButton;
    private FpsChartView chartView;

    private static final int BG = Color.rgb(0x10, 0x28, 0x30);
    private static final int SURFACE = Color.rgb(0x2A, 0x44, 0x4D);
    private static final int ACCENT = Color.rgb(0x5E, 0xA7, 0xFF);
    private static final int TEXT = Color.rgb(0xF2, 0xF5, 0xF6);
    private static final int MUTED = Color.rgb(0xB8, 0xC6, 0xCA);
    private static final int BORDER = Color.rgb(0x49, 0x63, 0x6B);

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateData();
            if (running) handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_benchmark_results);

        View root = findViewById(R.id.root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Simple status bar padding for API 26+
            root.setPadding(root.getPaddingLeft(), dp(32), root.getPaddingRight(), root.getPaddingBottom());
        }

        statusText = findViewById(R.id.statusText);
        durationText = findViewById(R.id.durationText);
        avgFpsText = findViewById(R.id.avgFpsText);
        minFpsText = findViewById(R.id.minFpsText);
        maxFpsText = findViewById(R.id.maxFpsText);
        avgTempText = findViewById(R.id.avgTempText);
        battDrainText = findViewById(R.id.battDrainText);
        startStopButton = findViewById(R.id.startStopButton);
        chartView = findViewById(R.id.chartView);

        TextView exportButton = findViewById(R.id.exportButton);
        TextView shareButton = findViewById(R.id.shareButton);

        styleButton(startStopButton, true);
        styleButton(exportButton, false);
        styleButton(shareButton, false);

        startStopButton.setOnClickListener(v -> toggleBenchmark());
        exportButton.setOnClickListener(v -> exportJson());
        shareButton.setOnClickListener(v -> shareJson());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void toggleBenchmark() {
        if (running) {
            ZenithDaemonClient.INSTANCE.stopBenchmark();
            running = false;
            statusText.setText("Stopped");
            startStopButton.setText("START");
            styleButton(startStopButton, true);
        } else {
            fpsHistory.clear();
            ZenithDaemonClient.INSTANCE.startBenchmark();
            running = true;
            statusText.setText("Running…");
            startStopButton.setText("STOP");
            styleButton(startStopButton, false);
            handler.post(refreshRunnable);
        }
    }

    private void updateData() {
        try {
            ZenithDaemonClient.BenchmarkData data = ZenithDaemonClient.INSTANCE.getBenchmarkData();
            if (data == null) {
                if (running) handler.postDelayed(refreshRunnable, 1000);
                return;
            }

            long elapsedMs = data.getElapsedMs();
            durationText.setText(formatDuration(elapsedMs));

            ZenithDaemonClient.FpsResponse fps = data.getFps();
            if (fps != null) {
                avgFpsText.setText(String.format(Locale.US, "%.1f", fps.getAvgFps() / 10.0));
                minFpsText.setText(String.format(Locale.US, "%.1f", fps.getMinFps() / 10.0));
                maxFpsText.setText(String.format(Locale.US, "%.1f", fps.getMaxFps() / 10.0));
            }

            // Record FPS history for chart (1 sample per second)
            if (fps != null && data.getElapsedMs() > 0) {
                long tsSec = elapsedMs / 1000;
                boolean exists = false;
                for (FpsSample s : fpsHistory) {
                    if (s.tsSec == tsSec) { exists = true; break; }
                }
                if (!exists) {
                    fpsHistory.add(new FpsSample(tsSec, fps.getAvgFps() / 10.0));
                    // Keep last 300 samples (5 min)
                    while (fpsHistory.size() > 300) fpsHistory.remove(0);
                    chartView.setData(fpsHistory);
                }
            }

            // Temperature and battery from daemon status
            ZenithDaemonClient.StatusResponse status = ZenithDaemonClient.INSTANCE.getStatus();
            if (status != null) {
                double avgTemp = 0;
                List<ZenithDaemonClient.ThermalZone> zones = status.getThermalZones();
                if (!zones.isEmpty()) {
                    double sum = 0;
                    for (ZenithDaemonClient.ThermalZone z : zones) sum += z.getTempC();
                    avgTemp = sum / zones.size();
                }
                avgTempText.setText(String.format(Locale.US, "%.1f°C", avgTemp));
                double drain = status.getBatteryDrainPctPerHr();
                battDrainText.setText(String.format(Locale.US, "%.1f%%/h", drain));
            }

            if (!data.getRunning() && running) {
                running = false;
                statusText.setText("Completed");
                startStopButton.setText("START");
                styleButton(startStopButton, true);
            }

            if (running) handler.postDelayed(refreshRunnable, 1000);
        } catch (Exception e) {
            if (running) handler.postDelayed(refreshRunnable, 1500);
        }
    }

    private void exportJson() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getExternalFilesDir(null);
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (dir == null) { Toast.makeText(this, "Cannot write file", Toast.LENGTH_SHORT).show(); return; }

            File file = new File(dir, "zenith_benchmark_" + ts + ".json");
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(buildJson().toString(2));
            }

            Toast.makeText(this, "Saved: " + file.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareJson() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getExternalFilesDir(null);
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (dir == null) { Toast.makeText(this, "Cannot create file", Toast.LENGTH_SHORT).show(); return; }

            File file = new File(dir, "zenith_benchmark_" + ts + ".json");
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(buildJson().toString(2));
            }

            Uri uri = android.net.Uri.fromFile(file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "Zenith Benchmark Results");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share Benchmark"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private JSONObject buildJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("timestamp", System.currentTimeMillis());
        obj.put("durationMs", fpsHistory.isEmpty() ? 0 :
            (fpsHistory.get(fpsHistory.size() - 1).tsSec - fpsHistory.get(0).tsSec) * 1000);

        ZenithDaemonClient.BenchmarkData data = ZenithDaemonClient.INSTANCE.getBenchmarkData();
        if (data != null) {
            obj.put("elapsedMs", data.getElapsedMs());
            obj.put("totalFrames", data.getFrames());
            ZenithDaemonClient.FpsResponse fps = data.getFps();
            if (fps != null) {
                JSONObject fpsObj = new JSONObject();
                fpsObj.put("avg", fps.getAvgFps() / 10.0);
                fpsObj.put("min", fps.getMinFps() / 10.0);
                fpsObj.put("max", fps.getMaxFps() / 10.0);
                obj.put("fps", fpsObj);
            }
        }

        JSONArray pts = new JSONArray();
        for (FpsSample s : fpsHistory) {
            JSONObject p = new JSONObject();
            p.put("timeMs", s.tsSec * 1000);
            p.put("fps", Math.round(s.fps * 10.0) / 10.0);
            pts.put(p);
        }
        obj.put("fpsHistory", pts);
        return obj;
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60) return m + "m " + s + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void styleButton(TextView btn, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : SURFACE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), BORDER);
        btn.setBackground(bg);
    }

}
