package com.example.reelsguard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

public final class MainActivity extends Activity {
    static final String PREFS = "reels_guard_settings";
    static final String BLOCK_REELS = "block_reels";
    static final String EXPLORE_SHIELD = "explore_shield";
    private TextView serviceStatus;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 248, 250));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        content.setPadding(pad, dp(32), pad, dp(30));
        scroll.addView(content);

        TextView title = text("Reels Guard", 29, Color.rgb(30, 38, 51));
        content.addView(title);
        TextView version = text("Version 1.4 • Instagram only", 15, Color.DKGRAY);
        version.setPadding(0, dp(5), 0, dp(18));
        content.addView(version);

        serviceStatus = text("", 17, Color.rgb(30, 38, 51));
        serviceStatus.setPadding(0, 0, 0, dp(12));
        content.addView(serviceStatus);

        Button settings = new Button(this);
        settings.setText("Open Accessibility settings");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        content.addView(settings);

        addToggle(content, prefs, BLOCK_REELS, true,
                "Block Reels", "Navigate away when a Reel viewer is confidently detected. Each viewer is handled once to prevent repeated Back actions.");
        addToggle(content, prefs, EXPLORE_SHIELD, true,
                "Explore swipe shield (experimental)", "While viewing a post opened from Explore, intercept vertical swipes. Forward taps and horizontal photo swipes. Pause the shield in comments.");

        TextView details = text(
                "How it works\n\n" +
                "• Reels Guard watches accessibility events from Instagram only.\n" +
                "• The Explore shield uses an accessibility overlay. It may occasionally interfere with Instagram interactions; turn it off if that happens.\n" +
                "• Comments can be scrolled normally once the comments sheet is recognized.\n" +
                "• The app does not collect, upload or store Instagram content.\n\n" +
                "If you installed v1.3 with a different signing key, Android may ask you to uninstall it before installing this debug build. You will then need to enable the service again.",
                14, Color.DKGRAY);
        details.setPadding(0, dp(24), 0, 0);
        content.addView(details);
        setContentView(scroll);
    }

    private void addToggle(LinearLayout content, SharedPreferences prefs, String key,
                           boolean defaultValue, String heading, String description) {
        Switch toggle = new Switch(this);
        toggle.setText(heading);
        toggle.setTextSize(17);
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        toggle.setPadding(0, dp(20), 0, dp(6));
        toggle.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        content.addView(toggle);
        TextView help = text(description, 14, Color.DKGRAY);
        help.setPadding(0, 0, 0, dp(8));
        content.addView(help);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        if (serviceStatus == null) return;
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean active = false;
        if (manager != null) {
            List<AccessibilityServiceInfo> enabled =
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : enabled) {
                if (info.getId() != null && info.getId().startsWith(getPackageName() + "/")) {
                    active = true;
                    break;
                }
            }
        }
        serviceStatus.setText(active ? "✓ Accessibility service enabled" : "Accessibility service is OFF");
        serviceStatus.setTextColor(active ? Color.rgb(31, 117, 71) : Color.rgb(154, 83, 22));
    }
}
