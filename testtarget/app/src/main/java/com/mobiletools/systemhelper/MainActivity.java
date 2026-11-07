package com.mobiletools.systemhelper;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 测试靶应用：模拟 SystemHelper 的 DNS 解析与 DM 上报行为。
 * 每 10 秒探测一次，把结果写到 logcat（TAG=TestTarget）和界面上。
 */
public class MainActivity extends Activity {

    private static final String TAG = "TestTarget";
    private static final String[] DOMAINS = {"dm.wo.com.cn", "track.analytics.oppo.com", "adservice.google.com", "api.map.baidu.com"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder lines = new StringBuilder();
    private TextView output;

    private final Runnable probeTask = new Runnable() {
        @Override
        public void run() {
            new Thread(MainActivity.this::probe, "testtarget-probe").start();
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setTextSize(13f);
        output.setPadding(32, 48, 32, 48);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);
        append("TestTarget started");
        handler.post(probeTask);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(probeTask);
        super.onDestroy();
    }

    private void probe() {
        for (String domain : DOMAINS) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                StringBuilder result = new StringBuilder();
                for (InetAddress address : addresses) {
                    if (result.length() > 0) result.append(", ");
                    result.append(address.getHostAddress());
                }
                report("DNS " + domain + " -> " + result);
            } catch (Throwable error) {
                report("DNS " + domain + " -> FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://dm.wo.com.cn:18080/register").openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(3_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.getOutputStream().write(new byte[]{'{', '}'});
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) stream.close();
            report("HTTP dm.wo.com.cn:18080 -> " + code);
        } catch (Throwable error) {
            report("HTTP dm.wo.com.cn:18080 -> FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void report(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        Log.i(TAG, message);
        handler.post(() -> append(time + "  " + message));
    }

    private void append(String message) {
        lines.insert(0, message + "\n");
        if (lines.length() > 4000) {
            lines.setLength(4000);
        }
        if (output != null) {
            output.setText(lines.toString());
        }
    }
}
