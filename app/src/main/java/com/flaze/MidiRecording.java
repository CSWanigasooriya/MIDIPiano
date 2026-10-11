package com.flaze;

import java.io.*;
import java.util.*;

/** Bounded take and portable Standard MIDI File (format 0, millisecond timing). */
public final class MidiRecording {
    public static final long MAX_DURATION_MS = 5 * 60 * 1000;
    public static final int MAX_EVENTS = 20000;
    public static final class Event {
        public final long timeMs;
        public final int status, data1, data2;
        public Event(long timeMs, int status, int data1, int data2) {
            this.timeMs = Math.max(0, timeMs); this.status = status; this.data1 = data1; this.data2 = data2;
        }
    }
    private final List<Event> events = new ArrayList<>();
    public void clear() { events.clear(); }
    public boolean add(long time, int status, int data1, int data2) {
        if (events.size() >= MAX_EVENTS || time > MAX_DURATION_MS) return false;
        events.add(new Event(time, status, data1, data2)); return true;
    }
    public boolean hasNotes() { for (Event e : events) if (e.status == 0x90 && e.data2 > 0) return true; return false; }
    public List<Event> snapshot() { return new ArrayList<>(events); }
    public byte[] toMidi() throws IOException {
        ByteArrayOutputStream trackBytes = new ByteArrayOutputStream();
        DataOutputStream track = new DataOutputStream(trackBytes);
        // Tempo 500000us/quarter, 500 ticks/quarter => one tick per millisecond.
        track.write(new byte[]{0, (byte) 0xff, 0x51, 3, 7, (byte) 0xa1, 0x20});
        long previous = 0;
        for (Event e : events) {
            writeVariable(track, Math.max(0, e.timeMs - previous)); previous = e.timeMs;
            track.writeByte(e.status); track.writeByte(e.data1);
            if ((e.status & 0xf0) != 0xc0) track.writeByte(e.data2);
        }
        // Ensure exports cannot leave held notes or a sustain pedal active.
        track.write(new byte[]{0, (byte) 0xb0, 64, 0, 0, (byte) 0xb0, 123, 0, 0, (byte) 0xff, 0x2f, 0});
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.writeBytes("MThd"); out.writeInt(6); out.writeShort(0); out.writeShort(1); out.writeShort(500);
        out.writeBytes("MTrk"); out.writeInt(trackBytes.size()); trackBytes.writeTo(out);
        return file.toByteArray();
    }
    private static void writeVariable(DataOutputStream out, long value) throws IOException {
        int buffer = (int) (value & 127);
        while ((value >>= 7) > 0) { buffer <<= 8; buffer |= (value & 127) | 128; }
        while (true) { out.writeByte(buffer); if ((buffer & 128) == 0) break; buffer >>= 8; }
    }
}
