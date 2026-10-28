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
    private final Handler handler = new Handler(Looper.getMainLooper());

    // One escape per Reels entry, never a stream of global Back presses.
    private boolean entryHandled = false;
    private boolean pendingTabTap = false;
    private long lastScan = 0L;
    private long lastEscape = 0L;

    private final Runnable afterTabTap = () -> {
        if (!pendingTabTap) return;
        pendingTabTap = false;
        AccessibilityNodeInfo root = instagramRoot();
        // An explicit tap on the Reels tab is sufficient; do not rely on generic
        // "Like", "Comment" or "Share" buttons also present on ordinary posts.
        if (root != null && !entryHandled) escapeOnce(root);
    };

    private final Runnable rearmAfterSafeScreen = () -> {
        if (!isEnabled()) return;
        AccessibilityNodeInfo root = instagramRoot();
        if (root != null && !isReelsViewer(root) && isDefinitelySafeScreen(root)) entryHandled = false;
    };

    private boolean isEnabled() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_BLOCK, true);
    }

    private AccessibilityNodeInfo instagramRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null &&
                INSTAGRAM.contentEquals(root.getPackageName()) ? root : null;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null ||
                !INSTAGRAM.contentEquals(event.getPackageName())) return;
        if (!isEnabled()) {
            pendingTabTap = false;
            entryHandled = false;
            handler.removeCallbacks(afterTabTap);
            handler.removeCallbacks(rearmAfterSafeScreen);
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isReelsTabClick(event.getSource())) {
                // Only a new user navigation event starts a new block attempt.
                if (SystemClock.uptimeMillis() - lastEscape > 1800L) {
                    pendingTabTap = true;
                    entryHandled = false;
                    handler.removeCallbacks(afterTabTap);
                    handler.postDelayed(afterTabTap, 450L);
                }
            }
            return;
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastScan < 300L) return;
        lastScan = now;
        AccessibilityNodeInfo root = instagramRoot();
        if (root == null) return;

        if (entryHandled) {
            // Once we have escaped, ONLY a recognizably non-Reels screen can
            // re-arm the blocker. A temporary change in Reel controls cannot.
            if (!isReelsViewer(root) && isDefinitelySafeScreen(root)) {
                handler.removeCallbacks(rearmAfterSafeScreen);
                handler.postDelayed(rearmAfterSafeScreen, 1900L);
            }
            return;
        }

        if (isReelsViewer(root)) escapeOnce(root);
    }

    private int screenHeight() { return getResources().getDisplayMetrics().heightPixels; }
    private int screenWidth() { return getResources().getDisplayMetrics().widthPixels; }
    private String lower(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isReelsLabel(CharSequence s) {
        String v = lower(s);
        return v.equals("reels") || v.equals("reel") ||
                v.startsWith("reels,") || v.startsWith("reel,") ||
                v.startsWith("reels tab") || v.startsWith("reel tab") ||
                v.equals("ver reels") || v.equals("ver reel");
    }

    private boolean isHomeLabel(CharSequence s) {
        String v = lower(s);
        return v.equals("home") || v.equals("home tab") || v.equals("home, selected") ||
                v.equals("início") || v.equals("inicio") || v.equals("página inicial") ||
                v.equals("pagina inicial") || v.startsWith("home,") ||
                v.startsWith("início,") || v.startsWith("página inicial,");
    }

    private boolean isReelsTabClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.centerY() < screenHeight() * .72f || r.width() > screenWidth() * .45f) return false;
        if (isReelsLabel(node.getContentDescription()) || isReelsLabel(node.getText())) return true;
        for (int i=0; i<Math.min(node.getChildCount(), 6); i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if (child!=null && (isReelsLabel(child.getContentDescription()) ||
                    isReelsLabel(child.getText()))) return true;
        }
        return false;
    }

    private boolean isReelsViewer(AccessibilityNodeInfo root) {
        boolean header=false, selectedTab=false, like=false, comment=false, share=false;
        int h=screenHeight(), w=screenWidth();
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.push(root);
        int limit=0;
        while(!nodes.isEmpty() && limit++<420) {
            AccessibilityNodeInfo n=nodes.pop();
            Rect r=new Rect();
            n.getBoundsInScreen(r);
            String value=lower(n.getContentDescription())+" "+lower(n.getText());
            boolean reelLabel=isReelsLabel(n.getText()) || isReelsLabel(n.getContentDescription());
            if(reelLabel) {
                if(r.centerY()>0 && r.centerY()<h*.24f && r.centerX()<w*.75f) header=true;
                if(r.centerY()>h*.76f && (n.isSelected() ||
                        value.contains("selected") || value.contains("selecionad"))) selectedTab=true;
            }
            if(r.centerX()>w*.64f && r.centerY()>h*.28f && r.centerY()<h*.95f) {
                if(value.contains("like") || value.contains("gosto") || value.contains("curtir")) like=true;
                if(value.contains("comment") || value.contains("coment")) comment=true;
                if(value.contains("share") || value.contains("send") ||
                        value.contains("partilh") || value.contains("enviar")) share=true;
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null) nodes.push(child);
            }
        }
        int buttons=(like?1:0)+(comment?1:0)+(share?1:0);
        // Never identify a Reel by generic action buttons alone.
        return (header && buttons>=2) || (selectedTab && buttons>=1);
    }

    private boolean isDefinitelySafeScreen(AccessibilityNodeInfo root) {
        int h=screenHeight();
        ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();
        nodes.push(root);
        int seen=0;
        while(!nodes.isEmpty() && seen++<420) {
            AccessibilityNodeInfo n=nodes.pop();
            Rect r=new Rect();
            n.getBoundsInScreen(r);
            String desc=lower(n.getContentDescription());
            String text=lower(n.getText());
            if (r.centerY()>h*.76f && (n.isSelected() ||
                    desc.contains("selected") || desc.contains("selecionad"))) {
                if (isHomeLabel(n.getContentDescription()) || isHomeLabel(n.getText()) ||
                        text.equals("search") || text.equals("pesquisar") || text.equals("profile") ||
                        text.equals("perfil") || desc.startsWith("search") ||
                        desc.startsWith("pesquisar") || desc.startsWith("profile") ||
                        desc.startsWith("perfil")) return true;
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null) nodes.push(child);
            }
        }
        return false;
    }

    private boolean clickHomeTab(AccessibilityNodeInfo root) {
        int h=screenHeight(), w=screenWidth();
        ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();
        nodes.push(root);
        int seen=0;
        while(!nodes.isEmpty() && seen++<420) {
            AccessibilityNodeInfo n=nodes.pop();
            Rect r=new Rect();
            n.getBoundsInScreen(r);
            if(r.centerY()>h*.75f && r.width()<w*.45f &&
                    (isHomeLabel(n.getContentDescription()) || isHomeLabel(n.getText()))) {
                AccessibilityNodeInfo clickable=n;
                for(int hops=0;hops<3 && clickable!=null;hops++) {
                    Rect outer=new Rect();
                    clickable.getBoundsInScreen(outer);
                    if(outer.width()>=w*.55f) break;
                    if(clickable.isClickable() &&
                            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    clickable=clickable.getParent();
                }
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null) nodes.push(child);
            }
        }
        return false;
    }

    private void escapeOnce(AccessibilityNodeInfo root) {
        long now=SystemClock.uptimeMillis();
        if (entryHandled || now-lastEscape<1800L || !isEnabled()) return;
        // Latch BEFORE doing anything; the action itself produces new events.
        entryHandled=true;
        pendingTabTap=false;
        lastEscape=now;
        handler.removeCallbacks(afterTabTap);
        handler.removeCallbacks(rearmAfterSafeScreen);

        // Navigate to Instagram Home if possible; fall back to ONE Back action.
        boolean escaped=clickHomeTab(root);
        if(!escaped) escaped=performGlobalAction(GLOBAL_ACTION_BACK);
        if(escaped) {
            int count=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE)
                    .getInt(MainActivity.KEY_COUNT,0);
            getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit()
                    .putInt(MainActivity.KEY_COUNT,count+1).apply();
        }
        // Rearm only if Home actually loaded, not just if Reel controls vanished.
        handler.postDelayed(rearmAfterSafeScreen, 2300L);
    }

    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        handler.removeCallbacks(afterTabTap);
        handler.removeCallbacks(rearmAfterSafeScreen);
        super.onDestroy();
    }
}
