package com.flaze;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.List;

/** One touch surface: independent fingers, glissando, and accessible virtual keys. */
public final class PianoKeyboardView extends View {
    private static final int[] WHITE = {0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23};
    private static final int[] BLACK = {1, 3, 6, 8, 10, 13, 15, 18, 20, 22};
    private static final int[] BLACK_AFTER = {0, 1, 3, 4, 5, 7, 8, 10, 11, 12};
    private static final String[] NAMES = {"C", "C sharp", "D", "D sharp", "E", "F", "F sharp", "G", "G sharp", "A", "A sharp", "B"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SparseIntArray fingers = new SparseIntArray();
    private final int[] held = new int[24];
    private final KeyAccessibility accessibility;
    private int base = 48;
    private int whiteCount = 7;
    private int touchGeneration;
    private boolean labels = true;
    private NoteListener listener;

    public PianoKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        accessibility = new KeyAccessibility(this);
        ViewCompat.setAccessibilityDelegate(this, accessibility);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    public void setNoteListener(NoteListener listener) { this.listener = listener; }
    public void setBase(int note) { releaseAll(); base = note; accessibility.invalidateRoot(); invalidate(); }
    public void setLabels(boolean value) { labels = value; invalidate(); }
    private float width() { return getWidth() / (float) whiteCount; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        releaseAll();
        whiteCount = w / getResources().getDisplayMetrics().density >= 700 ? 14 : 7;
        accessibility.invalidateRoot();
    }

    private RectF bounds(int key) {
        float w = width();
        for (int i = 0; i < BLACK.length; i++) if (BLACK[i] == key)
            return new RectF((BLACK_AFTER[i] + 1) * w - w * .31f, 0,
                    (BLACK_AFTER[i] + 1) * w + w * .31f, getHeight() * .61f);
        for (int i = 0; i < WHITE.length; i++) if (WHITE[i] == key)
            return new RectF(i * w, 0, (i + 1) * w, getHeight());
        return new RectF();
    }
    private int hit(float x, float y) {
        if (x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) return -1;
        for (int key : BLACK) if (key < (whiteCount == 7 ? 12 : 24) && bounds(key).contains(x, y)) return key;
        return WHITE[Math.min(whiteCount - 1, (int) (x / width()))];
    }
    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        for (int key : WHITE) if (key < (whiteCount == 7 ? 12 : 24)) drawKey(canvas, key, false);
        for (int key : BLACK) if (key < (whiteCount == 7 ? 12 : 24)) drawKey(canvas, key, true);
    }
    private void drawKey(Canvas canvas, int key, boolean black) {
        rect.set(bounds(key));
        rect.inset(1.5f, 2);
        paint.setColor(held[key] > 0 ? Color.rgb(100, 216, 192) : black ? Color.rgb(27, 34, 42) : Color.rgb(247, 248, 245));
        canvas.drawRoundRect(rect, 5, 5, paint);
        if (!black && labels) {
            paint.setColor(Color.rgb(66, 78, 85));
            paint.setTextSize(Math.min(width() * .32f, 14 * getResources().getDisplayMetrics().scaledDensity));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(NAMES[key % 12] + ((base + key) / 12 - 1), rect.centerX(), rect.bottom - 18, paint);
        }
    }
    private void press(int key) {
        if (key < 0) return;
        if (held[key]++ == 0 && listener != null) listener.onNoteOn(base + key);
        invalidate();
    }
    private void release(int key) {
        if (key < 0 || held[key] == 0) return;
        if (--held[key] == 0 && listener != null) listener.onNoteOff(base + key);
        invalidate();
    }
    public void releaseAll() {
        touchGeneration++;
        fingers.clear();
        for (int i = 0; i < held.length; i++) {
            if (held[i] > 0) { held[i] = 1; release(i); }
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return true;
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            int key = hit(event.getX(index), event.getY(index));
            fingers.put(event.getPointerId(index), key);
            press(key);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i), before = fingers.get(id, -1);
                int next = hit(event.getX(i), event.getY(i));
                if (before != next) { release(before); press(next); fingers.put(id, next); }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(index);
            release(fingers.get(id, -1)); fingers.delete(id);
            if (action == MotionEvent.ACTION_UP) { performClick(); getParent().requestDisallowInterceptTouchEvent(false); }
        } else if (action == MotionEvent.ACTION_CANCEL) releaseAll();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused,direction,previous);
        accessibility.onFocusChanged(focused,direction,previous);
    }
    private final class KeyAccessibility extends ExploreByTouchHelper {
        KeyAccessibility(View host) { super(host); }
        @Override protected int getVirtualViewAt(float x, float y) { int key = hit(x, y); return key < 0 ? INVALID_ID : key; }
        @Override protected void getVisibleVirtualViews(List<Integer> ids) { for (int i = 0; i < (whiteCount == 7 ? 12 : 24); i++) ids.add(i); }
        @Override protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            node.setContentDescription(NAMES[id % 12] + " " + ((base + id) / 12 - 1));
            Rect r = new Rect(); bounds(id).roundOut(r); node.setBoundsInParent(r);
            node.setClassName("android.widget.Button"); node.setClickable(true);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        }
        @Override protected boolean onPerformActionForVirtualView(int id, int action, Bundle args) {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || !isEnabled()) return false;
            int generation = touchGeneration;
            press(id); postDelayed(() -> { if (generation == touchGeneration) release(id); }, 250); return true;
        }
    }
    public interface NoteListener { void onNoteOn(int note); void onNoteOff(int note); }
}
