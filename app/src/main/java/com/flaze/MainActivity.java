package com.flaze;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;

import org.billthefarmer.mididriver.MidiDriver;

import flaze.R;

public class MainActivity extends Activity implements MidiDriver.OnMidiStartListener, View.OnTouchListener {

    private MidiDriver midiDriver;
    private byte[] event;
    private int[] instruments;
    private HorizontalScrollView scroller;
    private int scrollerWidth;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SeekBar seekBar = findViewById(R.id.seekBar);
        Spinner spinner = findViewById(R.id.selector);

        //MIDI Instrument Codes for Acoustic Grand Piano, Electric Grand Piano, Music Box, Acoustic Nylon Guitar, Trumpet, Flute
        instruments = new int[]{1, 10, 24, 40, 56, 73};

        scroller = findViewById(R.id.horizontalScroller);

        //Lower Octave
        Button buttonC3 = findViewById(R.id.c3);
        buttonC3.setOnTouchListener(this);
        Button buttonC3sharp = findViewById(R.id.c3Sharp);
        buttonC3sharp.setOnTouchListener(this);
        Button buttonD3 = findViewById(R.id.d3);
        buttonD3.setOnTouchListener(this);
        Button buttonD3sharp = findViewById(R.id.d3Sharp);
        buttonD3sharp.setOnTouchListener(this);
        Button buttonE3 = findViewById(R.id.e3);
        buttonE3.setOnTouchListener(this);
        Button buttonF3 = findViewById(R.id.f3);
        buttonF3.setOnTouchListener(this);
        Button buttonF3sharp = findViewById(R.id.f3Sharp);
        buttonF3sharp.setOnTouchListener(this);
        Button buttonG3 = findViewById(R.id.g3);
        buttonG3.setOnTouchListener(this);
        Button buttonG3sharp = findViewById(R.id.g3Sharp);
        buttonG3sharp.setOnTouchListener(this);
        Button buttonA3 = findViewById(R.id.a3);
        buttonA3.setOnTouchListener(this);
        Button buttonA3sharp = (findViewById(R.id.a3Sharp));
        buttonA3sharp.setOnTouchListener(this);
        Button buttonB3 = findViewById(R.id.b3);
        buttonB3.setOnTouchListener(this);

        //Middle Octave
        Button buttonC4 = findViewById(R.id.c4);
        buttonC4.setOnTouchListener(this);
        Button buttonC4sharp = findViewById(R.id.c4Sharp);
        buttonC4sharp.setOnTouchListener(this);
        Button buttonD4 = findViewById(R.id.d4);
        buttonD4.setOnTouchListener(this);
        Button buttonD4sharp = findViewById(R.id.d4Sharp);
        buttonD4sharp.setOnTouchListener(this);
        Button buttonE4 = findViewById(R.id.e4);
        buttonE4.setOnTouchListener(this);
        Button buttonF4 = findViewById(R.id.f4);
        buttonF4.setOnTouchListener(this);
        Button buttonF4sharp = findViewById(R.id.f4Sharp);
        buttonF4sharp.setOnTouchListener(this);
        Button buttonG4 = findViewById(R.id.g4);
        buttonG4.setOnTouchListener(this);
        Button buttonG4sharp = findViewById(R.id.g4Sharp);
        buttonG4sharp.setOnTouchListener(this);
        Button buttonA4 = findViewById(R.id.a4);
        buttonA4.setOnTouchListener(this);
        Button buttonA4sharp = (findViewById(R.id.a4Sharp));
        buttonA4sharp.setOnTouchListener(this);
        Button buttonB4 = findViewById(R.id.b4);
        buttonB4.setOnTouchListener(this);


        // Instantiate the driver.
        midiDriver = new MidiDriver();
        // Set the listener.
        midiDriver.setOnMidiStartListener(this);

        ViewTreeObserver vto = scroller.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                scroller.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                scrollerWidth = scroller.getChildAt(0)
                        .getMeasuredWidth()-getWindowManager().getDefaultDisplay().getWidth();
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                scroller.scrollTo((progress * scrollerWidth) / 100, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                selectInstrument(instruments[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                // your code here
            }

        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.midiDriver.start();

        // Get the configuration.
        int[] config = midiDriver.config();

        // Print out the details.
       /* Log.d(this.getClass().getName(), "maxVoices: " + config[0]);
        Log.d(this.getClass().getName(), "numChannels: " + config[1]);
        Log.d(this.getClass().getName(), "sampleRate: " + config[2]);
        Log.d(this.getClass().getName(), "mixBufferSize: " + config[3]);*/
    }

    @Override
    protected void onPause() {
        super.onPause();
        midiDriver.stop();
    }

    private void playNote(int note) {

        // Construct a note ON message for the middle C at maximum velocity on channel 1:
        event = new byte[3];
        event[0] = (byte) (0x90 | 0x00);  // 0x90 = note On, 0x00 = channel 1
        event[1] = (byte) note;
        event[2] = (byte) 0x7F;  // 0x7F = the maximum velocity (127)

        // Internally this just calls write() and can be considered obsoleted:
        //midiDriver.queueEvent(event);

        // Send the MIDI event to the synthesizer.
        midiDriver.write(event);

    }

    private void stopNote(int note) {

        // Construct a note OFF message for the middle C at minimum velocity on channel 1:
        event[0] = (byte) (0x80 | 0x00);  // 0x80 = note Off, 0x00 = channel 1
        event[1] = (byte) note;
        event[2] = (byte) 0x00;  // 0x00 = the minimum velocity (0)

        // Send the MIDI event to the synthesizer.
        midiDriver.write(event);

    }

    private void selectInstrument(int instrument) {

        // Construct a program change to select the instrument on channel 1:
        event = new byte[2];
        event[0] = (byte) (0xC0 | 0x00); // 0xC0 = program change, 0x00 = channel 1
        event[1] = (byte) instrument;

        // Send the MIDI event to the synthesizer.
        midiDriver.write(event);

    }

    @Override
    public void onMidiStart() {
        //..
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Log.d(this.getClass().getName(), "Motion event: " + event);

        try {
            int noteNumber = -1;
            for (int i = 3; i <= 4; i++) {

                int octave = i + 1;

                if (v.getTag().toString().equals("c" + i)) {
                    noteNumber = (octave * 12) ;
                }
                if (v.getTag().toString().equals("c" + i + "#")) {
                    noteNumber = (octave * 12) + 1;
                }
                if (v.getTag().toString().equals("d" + i)) {
                    noteNumber = (octave * 12) + 2;
                }
                if (v.getTag().toString().equals("d" + i + "#")) {
                    noteNumber = (octave * 12) + 3;
                }
                if (v.getTag().toString().equals("e" + i)) {
                    noteNumber = (octave * 12) + 4;
                }
                if (v.getTag().toString().equals("f" + i)) {
                    noteNumber = (octave * 12) + 5;
                }
                if (v.getTag().toString().equals("f" + i + "#")) {
                    noteNumber = (octave * 12) + 6;
                }
                if (v.getTag().toString().equals("g" + i)) {
                    noteNumber = (octave * 12) + 7;
                }
                if (v.getTag().toString().equals("g" + i + "#")) {
                    noteNumber = (octave * 12) + 8;
                }
                if (v.getTag().toString().equals("a" + i)) {
                    noteNumber = (octave * 12) + 9;
                }
                if (v.getTag().toString().equals("a" + i + "#")) {
                    noteNumber = (octave * 12) + 10;
                }
                if (v.getTag().toString().equals("b" + i)) {
                    noteNumber = (octave * 12) + 11;
                }
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Log.d(this.getClass().getName(), "MotionEvent.ACTION_DOWN");
                playNote(noteNumber);
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                Log.d(this.getClass().getName(), "MotionEvent.ACTION_UP");
                stopNote(noteNumber);
            }

        } catch (Exception e) {
            //..
        }
        return false;
    }
}