package com.example.reelsguard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;

public final class MainActivity extends Activity {
    static final String PREFS = "settings";
    static final String KEY_BLOCK = "block";
    static final String KEY_COUNT = "blocked";

    private TextView status;
    private TextView counter;
    private Switch toggle;
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private TextView label(String s, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(20,31,50));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(243,247,255));
        LinearLayout column = new LinearLayout(this);
        column.setPadding(dp(24), dp(40), dp(24), dp(24));
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        setContentView(scroll);

        TextView title = label("Reels Guard", 34, true);
        column.addView(title);
        column.addView(label("Keep Instagram DMs, Stories and regular posts. This app attempts to leave Reels automatically without repeatedly closing Instagram.",16,false));
        status = label("Checking settings...", 18, true);
        status.setPadding(0,dp(26),0,dp(14));
        column.addView(status);

        Button settings = new Button(this);
        settings.setText("Enable Accessibility Service");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        column.addView(settings, new LinearLayout.LayoutParams(-1,dp(60)));

        toggle = new Switch(this);
        toggle.setText("Block Instagram Reels");
        toggle.setTextSize(18);
        toggle.setPadding(0,dp(24),0,dp(16));
        toggle.setChecked(getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(KEY_BLOCK,true));
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(KEY_BLOCK,checked).apply();
            refresh();
        });
        column.addView(toggle);

        counter = label("0 Reels exit attempts",18,true);
        column.addView(counter);
        column.addView(label("How it works: Android Accessibility checks only Instagram and tries to open Home after detecting Reels. It presses Back at most once per entry if Home is unavailable. No Instagram content is stored or sent online.",14,false));
        column.addView(label("Experimental: some Reels may slip through, and certain Instagram screens may be redirected by mistake. You can turn it off whenever you want.",14,false));
    }

    @Override public void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        if (status == null || toggle == null || counter == null) return;
        if (!isServiceEnabled()) status.setText("Setup needed: enable Reels Guard blocker");
        else status.setText(toggle.isChecked() ? "Protection enabled" : "Protection paused");
        int n = getSharedPreferences(PREFS,MODE_PRIVATE).getInt(KEY_COUNT,0);
        counter.setText(n + " Reels exit attempts");
    }

    private boolean isServiceEnabled() {
        AccessibilityManager mgr = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (mgr == null) return false;
        ComponentName wanted = new ComponentName(this,ReelsWatchService.class);
        List<AccessibilityServiceInfo> enabled =
            mgr.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                ComponentName current = new ComponentName(
                    info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
                if (wanted.equals(current)) return true;
            }
        }
        return false;
    }
}
