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

    // Explore: allow the exact tile a person opens, but leave on the next
    // vertical scroll instead of playing an endless feed.
    private boolean exploreSection = false;
    private boolean explorePostOpen = false;
    private boolean exploreViewerSeen = false;
    private long exploreOpenedAt = 0L;
    private static final long EXPLORE_GRACE_MS = 950L;


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
            clearExploreGuard();
            exploreSection = false;
            pendingTabTap = false;
            entryHandled = false;
            handler.removeCallbacks(afterTabTap);
            handler.removeCallbacks(rearmAfterSafeScreen);
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo tapped = event.getSource();
            if (isExploreTabClick(tapped)) {
                exploreSection = true;
                clearExploreGuard();
                entryHandled = false;
                return;
            }
            if (isOtherTabClick(tapped)) {
                exploreSection = false;
                clearExploreGuard();
            }
            if (isReelsTabClick(tapped)) {
                exploreSection = false;
                clearExploreGuard();
                // Only a new user navigation event starts a new block attempt.
                if (SystemClock.uptimeMillis() - lastEscape > 1800L) {
                    pendingTabTap = true;
                    entryHandled = false;
                    handler.removeCallbacks(afterTabTap);
                    handler.postDelayed(afterTabTap, 450L);
                }
                return;
            }
            if (getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .getBoolean(MainActivity.KEY_EXPLORE, true) &&
                    (exploreSection || isExploreTabSelected(instagramRoot())) &&
                    (!explorePostOpen || isExploreGrid(instagramRoot())) &&
                    isExploreTileTap(tapped)) {
                exploreSection = true;
                explorePostOpen = true;
                exploreViewerSeen = false;
                exploreOpenedAt = SystemClock.uptimeMillis();
                // A tap from Explore is NOT itself a reason to close that post.
                handler.removeCallbacks(afterTabTap);
                pendingTabTap = false;
                entryHandled = false;
            }
            return;
        }

        // This event fires when a viewer's vertical list moves. Only respond
        // after an Explore tile was explicitly opened, never while browsing grid.
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (explorePostOpen && exploreViewerSeen &&
                    SystemClock.uptimeMillis() - exploreOpenedAt > EXPLORE_GRACE_MS &&
                    isPostFeedVerticalScroll(event)) {
                exitExploreViewerOnce();
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

        if (explorePostOpen) {
            if (exploreViewerSeen && isExploreGrid(root)) {
                // The original post was closed manually; the grid is safe again.
                clearExploreGuard();
            } else {
                if (now - exploreOpenedAt > 360L && !isExploreGrid(root)) {
                    exploreViewerSeen = true;
                }
                // Preserve the initially selected post, even if it is a video.
                // Don't let the older Reel detector close it before a swipe.
                if (explorePostOpen) return;
            }
        }

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

    private void clearExploreGuard() {
        explorePostOpen = false;
        exploreViewerSeen = false;
        exploreOpenedAt = 0L;
    }

    private boolean isExploreLabel(CharSequence s) {
        String v = lower(s);
        return v.equals("search") || v.equals("search tab") || v.equals("explore") ||
                v.equals("explore tab") || v.equals("pesquisar") ||
                v.equals("pesquisa") || v.equals("explorar") ||
                v.startsWith("search,") || v.startsWith("explore,") ||
                v.startsWith("pesquisar,") || v.startsWith("explorar,") ||
                v.startsWith("search and explore");
    }

    private boolean isExploreTabClick(AccessibilityNodeInfo n) {
        if (n == null) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.centerY() < screenHeight() * .73f || r.width() > screenWidth() * .5f) return false;
        if (isExploreLabel(n.getContentDescription()) || isExploreLabel(n.getText())) return true;
        for (int i=0; i<Math.min(6,n.getChildCount()); i++) {
            AccessibilityNodeInfo child=n.getChild(i);
            if (child!=null && (isExploreLabel(child.getContentDescription()) ||
                    isExploreLabel(child.getText()))) return true;
        }
        return false;
    }

    private boolean isOtherTabClick(AccessibilityNodeInfo n) {
        if (n == null) return false;
        Rect r=new Rect();
        n.getBoundsInScreen(r);
        if (r.centerY()<screenHeight()*.73f || r.width()>screenWidth()*.48f) return false;
        String v=lower(n.getText())+" "+lower(n.getContentDescription());
        return v.contains("home") || v.contains("início") || v.contains("pagina inicial") ||
                v.contains("página inicial") || v.contains("profile") || v.contains("perfil") ||
                v.contains("activity") || v.contains("atividade");
    }

    private boolean isExploreTabSelected(AccessibilityNodeInfo root) {
        if (root==null) return false;
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.push(root);
        int visited=0;
        while(!stack.isEmpty() && visited++<380) {
            AccessibilityNodeInfo n=stack.pop();
            Rect r=new Rect();
            n.getBoundsInScreen(r);
            String v=lower(n.getContentDescription());
            if (r.centerY()>screenHeight()*.73f &&
                    (isExploreLabel(n.getText()) || isExploreLabel(n.getContentDescription())) &&
                    (n.isSelected() || v.contains("selected") || v.contains("selecionad")))
                return true;
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null) stack.push(child);
            }
        }
        return false;
    }

    private boolean isExploreGrid(AccessibilityNodeInfo root) {
        if (root == null) return false;
        int h=screenHeight();
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.push(root);
        int visited=0;
        while(!stack.isEmpty() && visited++<400) {
            AccessibilityNodeInfo n=stack.pop();
            Rect r=new Rect();
            n.getBoundsInScreen(r);
            if (r.centerY()>0 && r.centerY()<h*.24f) {
                String v=lower(n.getText())+" "+lower(n.getContentDescription());
                String klass=lower(n.getClassName());
                if ((klass.contains("edittext") || v.contains("search") ||
                        v.contains("pesquisar") || v.contains("explorar")) &&
                        r.width()>screenWidth()*.3f) return true;
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null) stack.push(child);
            }
        }
        return false;
    }

    private boolean isExploreTileTap(AccessibilityNodeInfo n) {
        if (n==null) return false;
        Rect r=new Rect();
        n.getBoundsInScreen(r);
        int w=screenWidth(), h=screenHeight();
        if(r.centerY()<h*.16f || r.centerY()>h*.87f || r.width()>w*.76f ||
                r.width()<w*.07f || r.height()<h*.035f) return false;
        String v=lower(n.getText())+" "+lower(n.getContentDescription());
        if (v.contains("like") || v.contains("comment") || v.contains("share") ||
                v.contains("curtir") || v.contains("gosto") || v.contains("coment") ||
                v.contains("send") || v.contains("follow") || v.contains("seguir") ||
                v.contains("back") || v.contains("voltar")) return false;
        boolean tileShape=r.width()>w*.17f && r.width()<w*.57f && r.height()>h*.07f;
        boolean media=v.contains("photo") || v.contains("image") || v.contains("video") ||
                v.contains("reel") || v.contains("foto") || v.contains("imagem") ||
                v.contains("vídeo") || v.contains("publicação") || v.contains("post");
        return tileShape || media;
    }

    private boolean isPostFeedVerticalScroll(AccessibilityEvent event) {
        AccessibilityNodeInfo source=event.getSource();
        if (source==null) return false;
        Rect r=new Rect();
        source.getBoundsInScreen(r);
        if (r.width()<screenWidth()*.6f || r.height()<screenHeight()*.36f) return false;
        int dy=android.os.Build.VERSION.SDK_INT>=28 ? event.getScrollDeltaY() : 0;
        int dx=android.os.Build.VERSION.SDK_INT>=28 ? event.getScrollDeltaX() : 0;
        // Swiping up advances to the next post; horizontal carousel swipes are fine.
        return dy>24 && Math.abs(dy)>Math.abs(dx)*1.4f;
    }

    private void exitExploreViewerOnce() {
        // Mark handled before Back; its own accessibility events must never
        // trigger another Back, even if the next screen is slow to load.
        clearExploreGuard();
        entryHandled = true;
        lastEscape = SystemClock.uptimeMillis();
        handler.removeCallbacks(afterTabTap);
        pendingTabTap = false;
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            int count=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE)
                    .getInt(MainActivity.KEY_COUNT,0);
            getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit()
                    .putInt(MainActivity.KEY_COUNT,count+1).apply();
        }
        handler.removeCallbacks(rearmAfterSafeScreen);
        handler.postDelayed(rearmAfterSafeScreen, 2200L);
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
