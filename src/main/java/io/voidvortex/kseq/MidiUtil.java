package io.voidvortex.kseq;

import javax.sound.midi.*;

/**
 * Utility helpers for building javax.sound.midi structures without cluttering the core parser code.
 */
public final class MidiUtil {
    private MidiUtil() {
    }

    public static MidiEvent createShort(int command, int channel, int d1, int d2, long tick) {
        try {
            ShortMessage msg;
            if (command == ShortMessage.PROGRAM_CHANGE) {
                // Use status byte directly: 0xC0 | channel
                int status = command | (channel & 0x0F);
                msg = new ShortMessage(status, d1, 0);
            } else {
                msg = new ShortMessage(command, channel, d1, d2);
            }
            return new MidiEvent(msg, tick);
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Cannot build MIDI message", e);
        }
    }

    public static MetaMessage tempoMeta(int bpm) {
        int mpqn = (int) (60_000_000.0 / (bpm == 0 ? 120 : bpm));
        try {
            return new MetaMessage(0x51, new byte[]{
                    (byte) (mpqn >> 16),
                    (byte) (mpqn >> 8),
                    (byte) mpqn}, 3);
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MetaMessage textMeta(int type, String txt) {
        try {
            return new MetaMessage(type, txt.getBytes(), txt.length());
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException(e);
        }
    }

    // MidiUtil.java  (add at the end of the class)
    public static String noteName(int midiNote) {
        final String[] N = {"C", "C#", "D", "D#", "E", "F",
                "F#", "G", "G#", "A", "A#", "B"};
        if (midiNote < 0 || midiNote > 127) return "???";
        int octave = (midiNote / 12) - 2;          // SY77/MIDI convention
        return N[midiNote % 12] + octave;
    }

}