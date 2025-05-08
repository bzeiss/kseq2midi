/*
 * Copyright 2024 …
 *
 * Part of the kseq-to-midi library
 */
package io.voidvortex.kseq;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.ShortMessage;
import java.util.*;

import static io.voidvortex.kseq.KseqConstants.*;
import static io.voidvortex.kseq.MidiUtil.*;

/**
 * Translates the KSEQ event-stream into {@link MidiEvent}s
 * and optionally prints a human-readable trace.
 */
final class TrackParser {

    /* ─────────────── object state ───────────────────────────────────── */

    private final byte[] data;
    private final Map<Integer, List<MidiEvent>> target;
    private final Map<Integer, Integer> trackChannels;
    private final DebugSink dbg;                         // ⇐ may be NOOP
    private final int offsetShift;

    /* parsing cursor */
    private int ptr, tick;

    /* ─────────────── ctor ───────────────────────────────────────────── */

    TrackParser(byte[] data,
                Map<Integer, List<MidiEvent>> midiEvents,
                Map<Integer, Integer> trackChannels,
                DebugSink sink,
                int offsetShift) {

        this.data = data;
        this.target = midiEvents;
        this.trackChannels = trackChannels;
        this.dbg = Objects.requireNonNullElse(sink, DebugSink.NOOP);
        this.offsetShift = offsetShift;
    }

    /* ───────────────── public façade ────────────────────────────────── */

    /**
     * Parse the linear (song) area that starts at 0x1000.
     */
    void parseLinearTracks() {
        ptr = LINEAR_TRACK_DATA_OFFSET + offsetShift;
        tick = 0;
        int track = -1;

        while (ptr < data.length) {
            final int b = u8(data[ptr]);

            if (b == MARKER_SEQUENCE_END) break;

            /* ── track start ─────────────────────────────────────────── */
            if (b == MARKER_TRACK_START) {
                int nxt = u8(data[ptr + 1]);
                if (nxt == 0x0F) break;                 // pattern region begins

                track = nxt + 1;
                tick = 0;
                ensureTrack(track);

                dbg.log(hdr(tick, track, b)
                        + "TRACK_START");

                dbg.log("[DEBUG] Linear track " + track + " starts at ptr=" + ptr + ", tick=" + tick);
                ptr += 2;
                continue;
            }
            /* ── track end ───────────────────────────────────────────── */
            if (b == MARKER_TRACK_END) {
                dbg.log(hdr(tick, track, b)
                        + "TRACK_END");
                track = -1;
                ptr++;
                continue;
            }
            /* ── regular event inside a track ───────────────────────── */
            if (track != -1) {
                int consumed = parseEvent(track, ptr, tick);
                dbg.log("[DEBUG] Event in linear track " + track + " at ptr=" + ptr + ", tick=" + tick);
                tick += consumed >>> 16;
                ptr += consumed & 0xFFFF;
            } else {
                /* byte outside any track */
                dbg.log(hdr(tick, -1, b)
                        + "STRAY");
                ptr++;
            }
        }
    }

    /**
     * Parse an arbitrary byte-range (used by PatternUnfolder).
     */
    @SuppressWarnings("all")
    void parse(int start, int end, int initialTick, int trackNumber) {
        ensureTrack(trackNumber);
        ptr = start;
        tick = initialTick;
        int eventsBefore = target.get(trackNumber).size();
        while (ptr < end) {
            int consumed = parseEvent(trackNumber, ptr, tick);
            tick += consumed >>> 16;
            ptr += consumed & 0xFFFF;
        }
        int eventsAfter = target.get(trackNumber).size();
        System.out.println("TrackParser.parse: track=" + trackNumber + " start=" + start + " end=" + end + " tickOffset=" + initialTick + " eventsAdded=" + (eventsAfter - eventsBefore));
    }

    /**
     * Scans the linear track area and returns the tick of the first real note event (note-on).
     * Returns 0 if no note is found.
     */
    int findFirstNoteTick() {
        int scanPtr = LINEAR_TRACK_DATA_OFFSET + offsetShift;
        int scanTick = 0;
        int scanTrack = -1;
        while (scanPtr < data.length) {
            final int b = u8(data[scanPtr]);
            if (b == MARKER_SEQUENCE_END) break;
            if (b == MARKER_TRACK_START) {
                int nxt = u8(data[scanPtr + 1]);
                if (nxt == 0x0F) break; // pattern region begins
                scanTrack = nxt + 1;
                scanTick = 0;
                scanPtr += 2;
                continue;
            }
            if (b == MARKER_TRACK_END) {
                scanTrack = -1;
                scanPtr++;
                continue;
            }
            if (scanTrack != -1) {
                // Check for note events
                if ((b & 0xF0) == 0xD0) {
                    // long note
                    return scanTick;
                }
                if ((b & 0xF0) == 0xC0) {
                    // short note
                    return scanTick;
                }
                // Delta time handling (copy from parseEvent)
                if ((b & 0xE0) == 0xA0) {
                    int d = ((b & 0x1F) << 7) | u7(data[scanPtr + 1]);
                    scanTick += d;
                    scanPtr += 2;
                    continue;
                }
                if ((b & 0xE0) == 0x80) {
                    int d = b & 0x1F;
                    scanTick += d;
                    scanPtr += 1;
                    continue;
                }
                // Otherwise, skip event
                scanPtr++;
            } else {
                scanPtr++;
            }
        }
        return 0; // no note found
    }

    /* ───────────────────────── internals ───────────────────────────── */

    private void ensureTrack(int trk) {
        target.computeIfAbsent(trk, _ -> new ArrayList<>());
        trackChannels.putIfAbsent(trk, 1);          // default MIDI Ch-1
    }

    /**
     * Parse one logical event at {@code ptr}.
     *
     * @return (deltaTicks < < 16) | bytesConsumed
     */
    private int parseEvent(int track, int ptr, int currentTick) {

        final int b = u8(data[ptr]);
        final String head = hdr(currentTick, track, b);   // common prefix

        /* ─── delta-time ────────────────────────────────────────────── */
        if ((b & 0xE0) == 0xA0) {              // long delta
            int d = ((b & 0x1F) << 7) | u7(data[ptr + 1]);
            dbg.log(head + "Δ " + d);
            return (d << 16) | 2;
        }
        if ((b & 0xE0) == 0x80) {              // short delta
            int d = b & 0x1F;
            dbg.log(head + "Δ " + d);
            return (d << 16) | 1;
        }

        // Use channel 16 for pattern chain events
        final int ch = (track == VIRTUAL_PATTERN_TRACK) ? 15 : validChannel(track);
        final List<MidiEvent> list = target.get(track);

        /* ─── notes ─────────────────────────────────────────────────── */
        if ((b & 0xF0) == 0xD0) {              // long note
            int len = (((b & 0x0F) << 7) | u7(data[ptr + 1])) * 4;
            int note = u7(data[ptr + 2]);
            int vel = u7(data[ptr + 3]);
            list.add(createShort(ShortMessage.NOTE_ON, ch, note, vel, currentTick));
            list.add(createShort(ShortMessage.NOTE_OFF, ch, note, vel, currentTick + len));

            dbg.log(head + String.format("NOTE_LONG     %s vel=%d len=%d",
                    noteName(note), vel, len));
            return 4;
        }
        if ((b & 0xF0) == 0xC0) {              // short note
            int len = (b & 0x0F) * 4;
            int note = u7(data[ptr + 1]);
            int vel = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.NOTE_ON, ch, note, vel, currentTick));
            list.add(createShort(ShortMessage.NOTE_OFF, ch, note, vel, currentTick + len));

            dbg.log(head + String.format("NOTE_SHORT    %s vel=%d len=%d",
                    noteName(note), vel, len));
            return 3;
        }

        /* ─── channel messages ─────────────────────────────────────── */
        if (b == 0xFA && is7(data[ptr + 1], data[ptr + 2])) { // poly AT
            int note = u7(data[ptr + 1]);
            int pr = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.POLY_PRESSURE, ch, note, pr, currentTick));

            dbg.log(head + String.format("POLY_AT       %s pr=%d",
                    noteName(note), pr));
            return 3;
        }
        if (b == 0xFE && is7(data[ptr + 1], data[ptr + 2])) { // pitch bend
            int lsb = u7(data[ptr + 1]);
            int msb = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.PITCH_BEND, ch, lsb, msb, currentTick));

            int value = (msb << 7) | lsb;
            dbg.log(head + "PITCH_BEND    val=" + value);
            return 3;
        }
        if (b == 0xFB && is7(data[ptr + 1], data[ptr + 2])) { // control change
            int ctl = u7(data[ptr + 1]);
            int val = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.CONTROL_CHANGE, ch, ctl, val, currentTick));

            dbg.log(head + String.format("CONTROL_CHG   ctl=%d val=%d", ctl, val));
            return 3;
        }
        if (b == 0xFC && is7(data[ptr + 1])) {                // program change
            int pgm = u7(data[ptr + 1]);
            list.add(createShort(ShortMessage.PROGRAM_CHANGE, ch, pgm, 0, currentTick));

            dbg.log(head + "PROGRAM_CHG   pgm=" + pgm);
            return 2;
        }
        if (b == 0xFD && is7(data[ptr + 1])) {                // channel AT
            int pr = u7(data[ptr + 1]);
            list.add(createShort(ShortMessage.CHANNEL_PRESSURE, ch, pr, 0, currentTick));

            dbg.log(head + "CHANNEL_AT    pr=" + pr);
            return 2;
        }

        /* ─── meta-ish / ignored bytes ─────────────────────────────── */
        if (b == 0xF5) {
            dbg.log(head + "MEASURE_MARK");
            return 1;
        }
        if (b == 0xF8) {
            dbg.log(head + "NOP");
            return 1;
        }

        /* ─── unknown byte ─────────────────────────────────────────── */
        dbg.log(head + "UNKNOWN");
        return 1;
    }

    /* ───────── helpers ─────────────────────────────────────────────── */

    private static int u8(byte b) {
        return b & 0xFF;
    }

    private static int u7(byte b) {
        return b & 0x7F;
    }

    private static boolean is7(byte... bs) {
        for (byte b : bs) if ((b & 0x80) != 0) return false;
        return true;
    }

    /**
     * derive 0-based MIDI channel, clamped to 0-15
     */
    private int validChannel(int track) {
        int ch = trackChannels.getOrDefault(track, 1);
        return (ch < 1 || ch > 16 ? 1 : ch) - 1;
    }

    /* ───────── debug helpers ───────────────────────────────────────── */

    /**
     * common header for every log line
     */
    private static String hdr(int tick, int track, int firstByte) {
        return String.format("[t=%06d]  Trk%02d %s %-15s ",
                tick,
                track == -1 ? 0 : track,
                String.format("0x%02X", firstByte),
                kseqMnemonic(firstByte));
    }

    /**
     * first-byte → human mnemonic
     */
    private static String kseqMnemonic(int b) {
        return switch (b) {
            case 0xFC -> "PROGRAM_CHG";
            case 0xFB -> "CONTROL_CHG";
            case 0xFA -> "POLY_PRESS";
            case 0xFD -> "CHANNEL_AT";
            case 0xFE -> "PITCH_BEND";
            case 0xF0 -> "TRACK_START";
            case 0xF2 -> "TRACK_END";
            case 0xF5 -> "MEASURE_MRK";
            case 0xF8 -> "NOP";
            default -> {
                if ((b & 0xF0) == 0xC0) yield "NOTE_SHORT";
                if ((b & 0xF0) == 0xD0) yield "NOTE_LONG";
                yield "UNKNOWN";
            }
        };
    }
}
