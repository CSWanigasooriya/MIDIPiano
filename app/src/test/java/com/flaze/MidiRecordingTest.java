package com.flaze;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import static org.junit.Assert.*;

public class MidiRecordingTest {
    @Test public void exportedTakeIsReadableWithExactTimingAndProgram() throws Exception {
        MidiRecording take = new MidiRecording();
        take.add(0,0xc0,40,0);
        take.add(0,0x90,60,100);
        take.add(1000,0x80,60,0);
        take.add(1500,0x90,64,127);
        take.add(30000,0x80,64,0);
        Object sequence = parse(take.toMidi());
        assertEquals(500,call(sequence,"getResolution"));
        assertEquals(30000L,call(sequence,"getTickLength"));
        assertEquals(30000000L,call(sequence,"getMicrosecondLength"));
        Object track = ((Object[]) call(sequence,"getTracks"))[0];
        Object program = call(event(track,1),"getMessage");
        assertEquals(0xc0,call(program,"getCommand"));
        assertEquals(40,call(program,"getData1"));
        assertEquals(1000L,call(event(track,3),"getTick"));
        Object pedalOff = call(event(track,(Integer)call(track,"size")-3),"getMessage");
        assertEquals(64,call(pedalOff,"getData1")); assertEquals(0,call(pedalOff,"getData2"));
    }
    @Test public void rejectsOverlongTakesAndBoundsMemory() {
        MidiRecording take = new MidiRecording();
        assertFalse(take.add(MidiRecording.MAX_DURATION_MS+1,0x90,60,100));
        for (int i = 0; i < MidiRecording.MAX_EVENTS; i++) assertTrue(take.add(i,0x90,60,100));
        assertFalse(take.add(20000,0x90,60,100));
        assertEquals(MidiRecording.MAX_EVENTS,take.snapshot().size());
    }
    @Test public void blankTakeHasNoPlayableNotesAndSnapshotsAreIndependent() throws Exception {
        MidiRecording take = new MidiRecording(); take.add(0,0xc0,0,0);
        assertFalse(take.hasNotes());
        take.add(0,0x90,60,100); assertTrue(take.hasNotes());
        take.snapshot().clear(); assertTrue(take.hasNotes());
        take.clear(); assertFalse(take.hasNotes());
        assertNotNull(parse(take.toMidi()));
    }
    // Android's compiler API surface excludes java.desktop, even for local JVM tests.
    // Reflection lets the host JDK's independent MIDI parser verify the exported file.
    private static Object parse(byte[] bytes) throws Exception {
        return Class.forName("javax.sound.midi.MidiSystem").getMethod("getSequence",java.io.InputStream.class)
                .invoke(null,new ByteArrayInputStream(bytes));
    }
    private static Object call(Object target, String method) throws Exception {
        Class<?> type = target.getClass();
        // The JDK parser can return its internal FastShortMessage subclass.
        while (!type.getName().startsWith("javax.sound.midi.")) type = type.getSuperclass();
        return type.getMethod(method).invoke(target);
    }
    private static Object event(Object track, int index) throws Exception {
        return track.getClass().getMethod("get",int.class).invoke(track,index);
    }
}
