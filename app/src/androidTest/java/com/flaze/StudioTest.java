package com.flaze;

import android.view.MotionEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StudioTest {
    @Test public void keyboardGlissandoReleasesOldNoteAndCancellationClearsHeldNotes() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                PianoKeyboardView keyboard = activity.findViewById(R.id.pianoKeyboard);
                int width = (int)(350 * activity.getResources().getDisplayMetrics().density);
                keyboard.layout(0,0,width,300);
                List<String> notes = new ArrayList<>();
                keyboard.setNoteListener(new PianoKeyboardView.NoteListener() {
                    public void onNoteOn(int note) { notes.add("on:"+note); }
                    public void onNoteOff(int note) { notes.add("off:"+note); }
                });
                keyboard.setBase(48);
                touch(keyboard,MotionEvent.ACTION_DOWN,width/14f,280);
                touch(keyboard,MotionEvent.ACTION_MOVE,width*1.5f/7,280);
                touch(keyboard,MotionEvent.ACTION_CANCEL,width*1.5f/7,280);
                assertEquals(Arrays.asList("on:48","off:48","on:50","off:50"),notes);
            });
        }
    }
    @Test public void changingOctaveReleasesOriginalPitchAndDisabledKeyboardIsSilent() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                PianoKeyboardView keyboard = activity.findViewById(R.id.pianoKeyboard);
                int width = (int)(350 * activity.getResources().getDisplayMetrics().density);
                keyboard.layout(0,0,width,300);
                List<String> notes = new ArrayList<>();
                keyboard.setNoteListener(new PianoKeyboardView.NoteListener() {
                    public void onNoteOn(int note) { notes.add("on:"+note); }
                    public void onNoteOff(int note) { notes.add("off:"+note); }
                });
                keyboard.setBase(48); touch(keyboard,MotionEvent.ACTION_DOWN,20,280);
                keyboard.setBase(60); touch(keyboard,MotionEvent.ACTION_UP,20,280);
                keyboard.setEnabled(false); touch(keyboard,MotionEvent.ACTION_DOWN,20,280);
                assertEquals(Arrays.asList("on:48","off:48"),notes);
            });
        }
    }
    private static void touch(PianoKeyboardView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0,10,action,x,y,0);
        view.onTouchEvent(event); event.recycle();
    }
}
