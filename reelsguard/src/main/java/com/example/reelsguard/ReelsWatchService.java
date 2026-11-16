package com.example.reelsguard;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class ReelsWatchService extends AccessibilityService implements ExploreShield.Host {
    private static final String INSTAGRAM = "com.instagram.android";
    private static final long SCAN_DELAY_MS = 145L;
    private static final long EXPLORE_OPEN_WINDOW_MS = 2800L;
    private static final long REEL_REARM_MS = 1100L;
    private static final long EXIT_COOLDOWN_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanScheduled;
    private final Runnable scanTask = () -> {
        scanScheduled = false;
        scan();
    };
    private SharedPreferences prefs;
    private ExploreShield shield;
    private boolean exploreSection;
    private boolean explorePostOpen;
    private boolean reelsTabClicked;
    private boolean commentsOpen;
    private boolean reelExitAttempted;
    private boolean exploreExitAttempted;
    private long lastExploreTileClick;
    private long commentsPauseUntil;
    private long lastReelSeen;
    private long lastExit;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        shield = new ExploreShield(this, this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !INSTAGRAM.contentEquals(pkg)) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                resetForOtherApp();
            }
            return;
        }

        int type = event.getEventType();
        long now = SystemClock.uptimeMillis();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            String clicked = UiAnalyzer.label(source);
            if (UiAnalyzer.isExploreNav(clicked)) {
                exploreSection = true;
                explorePostOpen = false;
                reelsTabClicked = false;
                exploreExitAttempted = false;
            } else if (UiAnalyzer.isReelsNav(clicked)) {
                reelsTabClicked = true;
                exploreSection = false;
                explorePostOpen = false;
                stopShield();
            } else if (UiAnalyzer.isHomeNav(clicked)) {
                reelsTabClicked = false;
                exploreSection = false;
                explorePostOpen = false;
                stopShield();
            } else if (exploreSection && !explorePostOpen
                    && !clicked.contains("search") && !clicked.contains("comment")) {
                lastExploreTileClick = now;
            }
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                && explorePostOpen && !commentsOpen
                && now >= commentsPauseUntil
                && prefs != null && prefs.getBoolean(MainActivity.EXPLORE_SHIELD, true)
                && (shield == null || !shield.isVisible())
                && !UiAnalyzer.isCommentScrollSource(event.getSource())) {
            // Fallback if the overlay was unavailable: one Back per viewer.
            exitExploreViewerOnce();
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(scanTask);
            scanScheduled = true;
            handler.postDelayed(scanTask, 65L);
        } else if (!scanScheduled) {
            scanScheduled = true;
            handler.postDelayed(scanTask, SCAN_DELAY_MS);
        }
    }

    private void scan() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !INSTAGRAM.contentEquals(root.getPackageName())) {
            stopShield();
            return;
        }
        if (prefs == null) prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        UiAnalyzer.Snapshot ui = UiAnalyzer.inspect(root);
        long now = SystemClock.uptimeMillis();

        if (ui.exploreSelected) exploreSection = true;
        if (ui.exploreGrid) {
            explorePostOpen = false;
            exploreExitAttempted = false;
            lastExploreTileClick = 0L;
        }
        if (!explorePostOpen && exploreSection && ui.viewerActions && !ui.exploreGrid
                && lastExploreTileClick > 0L
                && now - lastExploreTileClick < EXPLORE_OPEN_WINDOW_MS) {
            explorePostOpen = true;
            exploreExitAttempted = false;
        }

        commentsOpen = ui.commentsOpen;
        boolean reelsViewer = ui.looksLikeReel() || (reelsTabClicked && ui.viewerActions);
        if (reelsViewer) {
            lastReelSeen = now;
            stopShield();
            if (prefs.getBoolean(MainActivity.BLOCK_REELS, true) && !reelExitAttempted
                    && now - lastExit >= EXIT_COOLDOWN_MS) {
                reelExitAttempted = true;
                lastExit = now;
                reelsTabClicked = false;
                explorePostOpen = false;
                // A Home tab action is less disruptive than repeated global Back.
                if (ui.homeButton == null ||
                        !ui.homeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }
            return;
        }
        if (now - lastReelSeen > REEL_REARM_MS) reelExitAttempted = false;

        boolean shouldShield = prefs.getBoolean(MainActivity.EXPLORE_SHIELD, true)
                && explorePostOpen && exploreSection && !commentsOpen
                && now >= commentsPauseUntil && !exploreExitAttempted;
        if (shield != null) shield.setActive(shouldShield);
    }

    private void exitExploreViewerOnce() {
        if (exploreExitAttempted) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastExit < EXIT_COOLDOWN_MS) return;
        exploreExitAttempted = true;
        lastExit = now;
        stopShield();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override public void onForwardingTap(float x, float y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (UiAnalyzer.isCommentButtonAt(root, x, y)) {
            commentsPauseUntil = SystemClock.uptimeMillis() + 1900L;
            stopShield();
            handler.postDelayed(() -> {
                if (!scanScheduled) {
                    scanScheduled = true;
                    handler.postDelayed(scanTask, 50L);
                }
            }, 1950L);
        }
    }

    private void stopShield() {
        if (shield != null) shield.setActive(false);
    }

    private void resetForOtherApp() {
        handler.removeCallbacks(scanTask);
        scanScheduled = false;
        stopShield();
        exploreSection = false;
        explorePostOpen = false;
        reelsTabClicked = false;
        commentsOpen = false;
        reelExitAttempted = false;
        exploreExitAttempted = false;
        lastExploreTileClick = 0L;
    }

    @Override public void onInterrupt() {
        stopShield();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (shield != null) shield.destroy();
        super.onDestroy();
    }
}
