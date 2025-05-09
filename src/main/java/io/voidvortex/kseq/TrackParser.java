/*
 * Copyright 2024 …
 *
 * Part of the kseq-to-midi library
 */
package io.voidvortex.kseq;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MetaMessage;
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
    private double currentKseqBpm; // Store current tempo in BPM
    private double initialTempoBpm; // Store initial tempo in BPM

    /* parsing cursor */
    private int ptr, tick;

    /* ─────────────── ctor ───────────────────────────────────────────── */

    TrackParser(byte[] data,
                Map<Integer, List<MidiEvent>> midiEvents,
                Map<Integer, Integer> trackChannels,
                DebugSink sink,
                int offsetShift,
                int initialTempoBpm) { // Added initialTempoBpm

        this.data = data;
        this.target = midiEvents;
        this.trackChannels = trackChannels;
        this.dbg = Objects.requireNonNullElse(sink, DebugSink.NOOP);
        this.offsetShift = offsetShift;
        this.initialTempoBpm = initialTempoBpm;
        this.currentKseqBpm = initialTempoBpm; // Initialize current BPM
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
                if (nxt == MARKER_PATTERN_EVENT) break;                 // pattern region begins

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
                if (nxt == MARKER_PATTERN_EVENT) break; // pattern region begins
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
                if ((b & MASK_NOTE_EVENT_TYPE) == TYPE_NOTE_LONG) { // long note
                    return scanTick;
                }
                if ((b & MASK_NOTE_EVENT_TYPE) == TYPE_NOTE_SHORT) { // short note
                    return scanTick;
                }
                // Delta time handling (copy from parseEvent)
                if ((b & MASK_DELTA_EVENT_TYPE) == TYPE_DELTA_LONG) {
                    int d = ((b & 0x1F) << 7) | u7(data[scanPtr + 1]);
                    scanTick += d;
                    scanPtr += 2;
                    continue;
                }
                if ((b & MASK_DELTA_EVENT_TYPE) == TYPE_DELTA_SHORT) {
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
        if ((b & MASK_DELTA_EVENT_TYPE) == TYPE_DELTA_LONG) {              // long delta
            int d = ((b & 0x1F) << 7) | u7(data[ptr + 1]);
            dbg.log(head + "Δ " + d);
            return (d << 16) | 2;
        }
        if ((b & MASK_DELTA_EVENT_TYPE) == TYPE_DELTA_SHORT) {              // short delta
            int d = b & 0x1F;
            dbg.log(head + "Δ " + d);
            return (d << 16) | 1;
        }

        // Use channel 16 for pattern chain events
        final int ch = (track == VIRTUAL_PATTERN_TRACK) ? 15 : validChannel(track);
        final List<MidiEvent> list = target.get(track);

        /* ─── notes ─────────────────────────────────────────────────── */
        if ((b & MASK_NOTE_EVENT_TYPE) == TYPE_NOTE_LONG) {              // long note
            int len = (((b & 0x0F) << 7) | u7(data[ptr + 1])) * 4;
            int note = u7(data[ptr + 2]);
            int vel = u7(data[ptr + 3]);
            list.add(createShort(ShortMessage.NOTE_ON, ch, note, vel, currentTick));
            list.add(createShort(ShortMessage.NOTE_OFF, ch, note, vel, currentTick + len));

            dbg.log(head + String.format("NOTE_LONG     %s vel=%d len=%d",
                    noteName(note), vel, len));
            return 4;
        }
        if ((b & MASK_NOTE_EVENT_TYPE) == TYPE_NOTE_SHORT) {              // short note
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
        if (b == MARKER_POLY_AFTERTOUCH && is7(data[ptr + 1], data[ptr + 2])) { // poly AT
            int note = u7(data[ptr + 1]);
            int pr = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.POLY_PRESSURE, ch, note, pr, currentTick));

            dbg.log(head + String.format("POLY_AT       %s pr=%d",
                    noteName(note), pr));
            return 3;
        }
        if (b == MARKER_PITCH_BEND && is7(data[ptr + 1], data[ptr + 2])) { // pitch bend
            int lsb = u7(data[ptr + 1]);
            int msb = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.PITCH_BEND, ch, lsb, msb, currentTick));

            int value = (msb << 7) | lsb;
            dbg.log(head + "PITCH_BEND    val=" + value);
            return 3;
        }
        if (b == MARKER_CONTROL_CHANGE && is7(data[ptr + 1], data[ptr + 2])) { // control change
            int ctl = u7(data[ptr + 1]);
            int val = u7(data[ptr + 2]);
            list.add(createShort(ShortMessage.CONTROL_CHANGE, ch, ctl, val, currentTick));

            dbg.log(head + String.format("CONTROL_CHG   ctl=%d val=%d", ctl, val));
            return 3;
        }
        if (b == MARKER_PROGRAM_CHANGE && is7(data[ptr + 1])) {                // program change
            int pgm = u7(data[ptr + 1]);
            list.add(createShort(ShortMessage.PROGRAM_CHANGE, ch, pgm, 0, currentTick));

            dbg.log(head + "PROGRAM_CHG   pgm=" + pgm);
            return 2;
        }
        if (b == MARKER_CHANNEL_AFTERTOUCH && is7(data[ptr + 1])) {                // channel AT
            int pr = u7(data[ptr + 1]);
            list.add(createShort(ShortMessage.CHANNEL_PRESSURE, ch, pr, 0, currentTick));

            dbg.log(head + "CHANNEL_AT    pr=" + pr);
            return 2;
        }

        // Tempo Change (Percentage-based)
        if (b == MARKER_TEMPO_CHANGE && is7(data[ptr + 1], data[ptr + 2])) {
            int byte1 = u7(data[ptr + 1]);
            int byte2 = u7(data[ptr + 2]);
            int kseqPercentageValue = (byte1 << 7) | byte2; // Raw value from KSEQ (0-16383)
            double tempoFromKseq = kseqPercentageValue / 10.0; // e.g., 1000 -> 100.0 (representing 100.0%)

            double newKseqBpm = initialTempoBpm * (tempoFromKseq/100);
            dbg.log("Tempo change: initial=" + initialTempoBpm + ", old=" + this.currentKseqBpm + ", new=" + newKseqBpm);
            this.currentKseqBpm = newKseqBpm;

            // Convert KSEQ BPM to MIDI tempo (microseconds per quarter note)
            int midiTempo = (this.currentKseqBpm > 0) ? (int) (60000000 / Math.round(this.currentKseqBpm)) : 0;

            byte[] tempoDataBytes = new byte[3];
            tempoDataBytes[0] = (byte) ((midiTempo >> 16) & 0xFF);
            tempoDataBytes[1] = (byte) ((midiTempo >> 8) & 0xFF);
            tempoDataBytes[2] = (byte) (midiTempo & 0xFF);

            try {
                MetaMessage tempoMessage = new MetaMessage(0x51, tempoDataBytes, 3); // MIDI Meta Event type for Set Tempo
                list.add(new MidiEvent(tempoMessage, currentTick)); // 'list' is defined earlier for the current track
                dbg.log(head + String.format("TEMPO_CHG_P   val=%d (%.1f%%) -> new_bpm=%.2f midi_tempo=%d",
                        kseqPercentageValue, tempoFromKseq, this.currentKseqBpm, midiTempo));
            } catch (Exception e) { // InvalidMidiDataException
                dbg.log(head + "TEMPO_CHG_P   ERROR creating tempo event: " + e.getMessage());
            }
            return 3; // Consumed 0xF3 + 2 data bytes
        }

        /* ─── meta-ish / ignored bytes ─────────────────────────────── */
        if (b == MARKER_MEASURE_MARK) {
            dbg.log(head + "MEASURE_MARK");
            return 1;
        }
        if (b == MARKER_NO_OPERATION) {
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
            case MARKER_PROGRAM_CHANGE -> "PROGRAM_CHG";
            case MARKER_CONTROL_CHANGE -> "CONTROL_CHG";
            case MARKER_POLY_AFTERTOUCH -> "POLY_PRESS";
            case MARKER_CHANNEL_AFTERTOUCH -> "CHANNEL_AT";
            case MARKER_PITCH_BEND -> "PITCH_BEND";
            case MARKER_TRACK_START -> "TRACK_START";
            case MARKER_TRACK_END -> "TRACK_END";
            case MARKER_MEASURE_MARK -> "MEASURE_MRK";
            case MARKER_NO_OPERATION -> "NOP";
            case MARKER_TEMPO_CHANGE -> "TEMPO_CHG_P"; // Added for percentage tempo change
            default -> {
                if ((b & MASK_NOTE_EVENT_TYPE) == TYPE_NOTE_SHORT) yield "NOTE_SHORT";
                if ((b & MASK_NOTE_EVENT_TYPE) == TYPE_NOTE_LONG) yield "NOTE_LONG";
                if ((b & MASK_DELTA_EVENT_TYPE) == TYPE_DELTA_SHORT) yield "DELTA_SHORT"; // Added for completeness
                if ((b & MASK_DELTA_EVENT_TYPE) == TYPE_DELTA_LONG) yield "DELTA_LONG";   // Added for completeness
                yield "UNKNOWN";
            }
        };
    }
}
