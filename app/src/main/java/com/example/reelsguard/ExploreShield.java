package com.example.reelsguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Experimental touch shield for an Explore post viewer. It intercepts a vertical
 * gesture before Instagram sees it. Taps and horizontal swipes are played back
 * using AccessibilityService.dispatchGesture after temporarily removing the
 * shield from touch handling. No content is recorded, transmitted, or saved.
 */
final class ExploreShield {
    interface Host {
        boolean shouldShield();
        void onVerticalSwipe();
        void onShieldTap(float x, float y);
    }

    private final AccessibilityService service;
    private final Host host;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View shieldView;
    private boolean installed = false;
    private boolean resending = false;
    private float downX, downY;
    private long downTime;

    ExploreShield(AccessibilityService service, Host host) {
        this.service = service;
        this.host = host;
        windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    private int dp(float v) {
        return (int) (v * service.getResources().getDisplayMetrics().density + .5f);
    }

    private void create() {
        FrameLayout frame = new FrameLayout(service);
        frame.setBackgroundColor(Color.TRANSPARENT);
        // Small visible status hint. Other than this tag, the touch area is transparent.
        TextView badge = new TextView(service);
        badge.setText("↕ Scroll blocked • Reels Guard");
        badge.setTextSize(11f);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        badge.setBackgroundColor(0xB42B4B80);
        FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        caption.topMargin = dp(4);
        frame.addView(badge, caption);
        frame.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX=event.getRawX();
                    downY=event.getRawY();
                    downTime=event.getEventTime();
                    return true;
                case MotionEvent.ACTION_UP:
                    float x=event.getRawX(), y=event.getRawY();
                    float dx=x-downX, dy=y-downY;
                    float limit=dp(58);
                    if (Math.abs(dy) >= limit &&
                            Math.abs(dy) > Math.abs(dx)*1.18f) {
                        hide();
                        host.onVerticalSwipe();
                        return true;
                    }
                    // Tap/hold and sideways photo-carousel swipes continue into Instagram.
                    if (Math.abs(dx)<dp(20) && Math.abs(dy)<dp(20)) {
                        host.onShieldTap(x,y);
                        replay(downX, downY, downX, downY,
                                Math.min(650L, Math.max(65L, event.getEventTime()-downTime)));
                    } else if (Math.abs(dx)>dp(35) &&
                            Math.abs(dx)>Math.abs(dy)*1.14f) {
                        replay(downX, downY, x, y, Math.min(550L,
                                Math.max(180L, event.getEventTime()-downTime)));
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });
        shieldView=frame;
    }

    void show() {
        if (resending || !host.shouldShield()) return;
        if (shieldView==null) create();
        if (installed) {
            shieldView.setVisibility(View.VISIBLE);
            return;
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        // No SYSTEM_ALERT_WINDOW permission needed: accessibility overlays are
        // available only while the user enables this service.
        try {
            windowManager.addView(shieldView,p);
            installed=true;
        } catch (RuntimeException ignored) {
            installed=false;
        }
    }

    void hide() {
        if (installed && shieldView!=null) shieldView.setVisibility(View.GONE);
    }

    private void replay(float startX, float startY, float endX, float endY, long ms) {
        resending=true;
        hide();
        handler.postDelayed(() -> {
            Path path = new Path();
            path.moveTo(startX,startY);
            if (Math.abs(endX-startX)>1f || Math.abs(endY-startY)>1f)
                path.lineTo(endX,endY);
            GestureDescription gesture=new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path,0,ms))
                    .build();
            boolean started;
            try {
                started=service.dispatchGesture(gesture,
                        new AccessibilityService.GestureResultCallback() {
                            @Override public void onCompleted(GestureDescription d) { doneReplaying(); }
                            @Override public void onCancelled(GestureDescription d) { doneReplaying(); }
                        },handler);
            } catch (RuntimeException e) {
                started=false;
            }
            if (!started) doneReplaying();
        },75L);
    }

    private void doneReplaying() {
        handler.postDelayed(() -> {
            resending=false;
            if(host.shouldShield()) show();
        },160L);
    }

    void remove() {
        handler.removeCallbacksAndMessages(null);
        resending=false;
        if (installed) {
            try { windowManager.removeViewImmediate(shieldView); }
            catch (RuntimeException ignored) {}
        }
        installed=false;
        shieldView=null;
    }
}
