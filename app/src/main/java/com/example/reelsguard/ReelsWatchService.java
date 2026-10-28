package com.example.reelsguard;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Locale;

public final class ReelsWatchService extends AccessibilityService {
    private static final String INSTAGRAM = "com.instagram.android";
    private long lastScan = 0L;
    private long lastBack = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean reelTapped = false;

    private final Runnable afterTap = () -> {
        if (!reelTapped) return;
        reelTapped = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null &&
                INSTAGRAM.contentEquals(root.getPackageName())) {
            goBack();
        }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null ||
                !INSTAGRAM.contentEquals(event.getPackageName())) return;
        if (!getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_BLOCK, true)) {
            reelTapped = false;
            handler.removeCallbacks(afterTap);
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isReelsClick(event.getSource())) {
                reelTapped = true;
                handler.removeCallbacks(afterTap);
                handler.postDelayed(afterTap, 450L);
            }
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        long time = SystemClock.uptimeMillis();
        if (time-lastScan < 250 || time-lastBack < 1400) return;
        lastScan = time;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null &&
                INSTAGRAM.contentEquals(root.getPackageName()) && isReelViewer(root)) {
            goBack();
        }
    }

    private boolean reelsLabel(CharSequence s) {
        if (s == null) return false;
        String v = s.toString().trim().toLowerCase(Locale.ROOT);
        return v.equals("reels") || v.equals("reel") || v.startsWith("reels,") ||
            v.startsWith("reels tab") || v.startsWith("reel tab") ||
            v.equals("watch reels") || v.equals("view reel") ||
            v.equals("ver reels") || v.equals("ver reel");
    }

    private boolean isReelsClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() > getResources().getDisplayMetrics().widthPixels*.65f) return false;
        if (reelsLabel(node.getText()) || reelsLabel(node.getContentDescription())) return true;
        for (int i=0; i<Math.min(node.getChildCount(),8); i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if (child!=null && (reelsLabel(child.getText()) || reelsLabel(child.getContentDescription()))) return true;
        }
        return false;
    }

    private String low(CharSequence s) {
        return s==null?"":s.toString().toLowerCase(Locale.ROOT);
    }

    private boolean isReelViewer(AccessibilityNodeInfo root) {
        int height=getResources().getDisplayMetrics().heightPixels;
        int width=getResources().getDisplayMetrics().widthPixels;
        boolean topReels=false, selectedReels=false, like=false,comment=false,share=false,audio=false;
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.push(root);
        int scanned=0;
        while (!stack.isEmpty() && scanned++<350) {
            AccessibilityNodeInfo node=stack.pop();
            Rect r=new Rect();
            node.getBoundsInScreen(r);
            String text=low(node.getText())+" "+low(node.getContentDescription());
            if (reelsLabel(node.getText())||reelsLabel(node.getContentDescription())) {
                if (r.centerY()>0 && r.centerY()<height*.30f && r.centerX()<width*.70f) topReels=true;
                if (r.centerY()>height*.70f && (node.isSelected()||text.contains("selected")||
                        text.contains("selecionad"))) selectedReels=true;
            }
            if (r.centerX()>width*.62f && r.centerY()>height*.30f && r.centerY()<height*.94f) {
                if (text.contains("like")||text.contains("gosto")||text.contains("curtir")) like=true;
                if (text.contains("comment")||text.contains("coment")) comment=true;
                if (text.contains("share")||text.contains("send")||text.contains("partilh")||
                        text.contains("enviar")) share=true;
                if (text.contains("audio")||text.contains("music")||text.contains("música")) audio=true;
            }
            for(int i=0;i<node.getChildCount();i++){
                AccessibilityNodeInfo child=node.getChild(i);
                if(child!=null) stack.push(child);
            }
        }
        int controls=(like?1:0)+(comment?1:0)+(share?1:0)+(audio?1:0);
        return controls>=3 || (topReels&&controls>=1) || (selectedReels&&controls>=2);
    }

    private void goBack() {
        long now=SystemClock.uptimeMillis();
        if (now-lastBack<1400 || !getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_BLOCK,true)) return;
        lastBack=now;
        reelTapped=false;
        handler.removeCallbacks(afterTap);
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            int count=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).getInt(MainActivity.KEY_COUNT,0);
            getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit()
                .putInt(MainActivity.KEY_COUNT,count+1).apply();
        }
    }

    @Override public void onInterrupt() {}
    @Override public void onDestroy(){ handler.removeCallbacks(afterTap); super.onDestroy(); }
}
