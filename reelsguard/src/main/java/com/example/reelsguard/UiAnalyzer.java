package com.example.reelsguard;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Locale;

final class UiAnalyzer {
    private UiAnalyzer() {}

    static final class Snapshot {
        boolean exploreSelected;
        boolean reelsSelected;
        boolean commentsOpen;
        boolean exploreGrid;
        boolean viewerActions;
        int reelCues;
        AccessibilityNodeInfo homeButton;

        boolean looksLikeReel() {
            // A static "Reels" bottom-navigation label is not enough to count as a viewer.
            return viewerActions && (reelsSelected || reelCues >= 2);
        }
    }

    static Snapshot inspect(AccessibilityNodeInfo root) {
        Snapshot snapshot = new Snapshot();
        if (root == null) return snapshot;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int nodes = 0, images = 0;
        boolean like = false, comment = false, share = false;
        boolean commentEditor = false, commentsHeading = false;

        while (!queue.isEmpty() && nodes++ < 280) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String label = label(node);
            String className = node.getClassName() == null ? "" : node.getClassName().toString().toLowerCase(Locale.ROOT);
            boolean selected = node.isSelected() || label.contains("selected");

            if (isExploreNav(label) && selected) snapshot.exploreSelected = true;
            if (isReelsNav(label) && selected) snapshot.reelsSelected = true;
            if (snapshot.homeButton == null && isHomeNav(label) && node.isClickable()) {
                snapshot.homeButton = node;
            }

            if (label.contains("original audio") || label.contains("use audio")
                    || label.contains("reels camera") || label.contains("remix reel")
                    || label.contains("watch more reels") || label.contains("reel viewer")) {
                snapshot.reelCues++;
            }

            if (isLikeButton(label)) like = true;
            if (isCommentButton(label)) comment = true;
            if (isShareButton(label)) share = true;
            if (label.equals("comments") || label.startsWith("comments,")
                    || label.startsWith("comments ")) commentsHeading = true;
            if (className.contains("edittext") &&
                    (label.contains("comment") || label.isEmpty())) commentEditor = true;
            if (label.contains("write a comment") || label.contains("add a comment")) commentEditor = true;
            if (className.contains("imageview") || className.contains("imagebutton")) images++;

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        snapshot.viewerActions = like && comment && share;
        snapshot.commentsOpen = commentEditor && (commentsHeading || comment);
        snapshot.exploreGrid = snapshot.exploreSelected && images >= 4 && !snapshot.viewerActions;
        return snapshot;
    }

    static String label(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence description = node.getContentDescription();
        CharSequence text = node.getText();
        String value = (description == null ? "" : description.toString())
                + " " + (text == null ? "" : text.toString());
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isExploreNav(String label) {
        return label.equals("explore") || label.startsWith("explore tab")
                || label.startsWith("search and explore")
                || label.startsWith("explore,");
    }
    static boolean isReelsNav(String label) {
        return label.equals("reels") || label.startsWith("reels tab")
                || label.startsWith("reels,");
    }
    static boolean isHomeNav(String label) {
        return label.equals("home") || label.startsWith("home tab")
                || label.startsWith("home,");
    }
    static boolean isLikeButton(String label) {
        return label.equals("like") || label.startsWith("like,")
                || label.equals("unlike") || label.startsWith("likes");
    }
    static boolean isCommentButton(String label) {
        return label.equals("comment") || label.startsWith("comment,")
                || label.equals("comments") || label.startsWith("view comments")
                || label.startsWith("add a comment");
    }
    static boolean isShareButton(String label) {
        return label.equals("share") || label.startsWith("share,")
                || label.startsWith("send post") || label.startsWith("send reel");
    }

    static boolean isCommentScrollSource(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 5; depth++, node = node.getParent()) {
            String name = label(node);
            if (name.contains("comments") || name.contains("comment list")) return true;
        }
        return false;
    }

    static boolean isCommentButtonAt(AccessibilityNodeInfo root, float x, float y) {
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        Rect bounds = new Rect();
        while (!queue.isEmpty() && count++ < 280) {
            AccessibilityNodeInfo node = queue.removeFirst();
            node.getBoundsInScreen(bounds);
            if (bounds.contains((int) x, (int) y)
                    && node.isClickable() && isCommentButton(label(node))) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }
}
