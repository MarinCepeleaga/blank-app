package com.example.reelsguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

// Experimental: intercept touch only while an Explore post viewer is active.
// Taps and horizontal gestures are re-dispatched to Instagram, not recorded.
final class ExploreShield {
    interface Host {
        void onForwardingTap(float screenX, float screenY);
    }

    private final AccessibilityService service;
    private final Host host;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View overlay;
    private boolean attached;
    private boolean desired;
    private boolean forwarding;
    private float downX, downY;
    private long downTime;

    ExploreShield(AccessibilityService service, Host host) {
        this.service = service;
        this.host = host;
        windows = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    boolean isVisible() {
        return attached && !forwarding;
    }

    void setActive(boolean value) {
        desired = value;
        if (forwarding) return;
        if (desired) show(); else hide();
    }

    void destroy() {
        desired = false;
        forwarding = false;
        hide();
    }

    private void show() {
        if (attached || windows == null) return;
        if (overlay == null) {
            overlay = new View(service);
            overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlay.setOnTouchListener((view, event) -> handleTouch(event));
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windows.addView(overlay, params);
            attached = true;
        } catch (RuntimeException ignored) {
            attached = false; // Restricted overlays should not crash Instagram or this service.
        }
    }

    private void hide() {
        if (!attached || windows == null || overlay == null) return;
        try {
            windows.removeViewImmediate(overlay);
        } catch (RuntimeException ignored) {
            // Window already removed by the system.
        }
        attached = false;
    }

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                downTime = event.getEventTime();
                return true;
            case MotionEvent.ACTION_UP:
                float upX = event.getRawX();
                float upY = event.getRawY();
                float dx = upX - downX;
                float dy = upY - downY;
                float threshold = 38f * service.getResources().getDisplayMetrics().density;
                if (Math.abs(dy) >= threshold && Math.abs(dy) > Math.abs(dx) * 1.15f) {
                    // Consume vertical navigation. Do NOT perform repeated Back actions.
                    return true;
                }
                boolean horizontal = Math.abs(dx) >= threshold
                        && Math.abs(dx) > Math.abs(dy) * 1.15f;
                if (!horizontal) host.onForwardingTap(upX, upY);
                replayGesture(upX, upY, horizontal);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_MOVE:
            default:
                return true;
        }
    }

    private void replayGesture(float upX, float upY, boolean horizontal) {
        if (forwarding) return;
        forwarding = true;
        hide(); // Prevent our overlay from receiving the synthesized gesture.
        Path path = new Path();
        path.moveTo(downX, downY);
        if (horizontal) {
            path.lineTo(upX, upY);
        } else {
            path.lineTo(downX + 0.5f, downY + 0.5f);
        }
        long duration = horizontal
                ? Math.max(100L, Math.min(420L, android.os.SystemClock.uptimeMillis() - downTime))
                : 65L;
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();
        boolean submitted;
        try {
            submitted = service.dispatchGesture(gesture,
                    new AccessibilityService.GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription result) {
                            finishForwarding();
                        }
                        @Override public void onCancelled(GestureDescription result) {
                            finishForwarding();
                        }
                    }, handler);
        } catch (RuntimeException exception) {
            submitted = false;
        }
        if (!submitted) finishForwarding();
    }

    private void finishForwarding() {
        handler.postDelayed(() -> {
            forwarding = false;
            if (desired) show();
        }, 80L);
    }
}
