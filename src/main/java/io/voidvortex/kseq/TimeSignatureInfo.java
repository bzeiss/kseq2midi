package io.voidvortex.kseq;

import javax.sound.midi.MetaMessage;
import java.util.Map;

public class TimeSignatureInfo {
    public final String humanReadable;
    public final int numerator;
    public final int denominator;
    public final MetaMessage metaMessage;

    public TimeSignatureInfo(String humanReadable, int numerator, int denominator) {
        this(humanReadable, numerator, denominator, null);
    }

    public TimeSignatureInfo(String humanReadable, int numerator, int denominator, MetaMessage metaMessage) {
        this.humanReadable = humanReadable;
        this.numerator = numerator;
        this.denominator = denominator;
        this.metaMessage = metaMessage;
    }

    public TimeSignatureInfo withMetaMessage() {
        if (metaMessage != null) return this;
        int midiDenom = 0;
        int d = denominator;
        while (d > 1) {
            d >>= 1;
            midiDenom++;
        }
        byte[] data = new byte[]{
                (byte) numerator,
                (byte) midiDenom,
                24, // MIDI Clocks per metronome click (default: 24 = quarter note)
                8   // 32nd notes per 24 MIDI clocks (default: 8)
        };
        MetaMessage msg;
        try {
            msg = new MetaMessage(0x58, data, 4);
        } catch (javax.sound.midi.InvalidMidiDataException e) {
            throw new IllegalStateException(e);
        }
        return new TimeSignatureInfo(humanReadable, numerator, denominator, msg);
    }

    private static final Map<Integer, TimeSignatureInfo> TIME_SIGNATURE_MAP;

    static {
        TIME_SIGNATURE_MAP = Map.ofEntries(
                Map.entry(0x04, new TimeSignatureInfo("1/4", 1, 4)),
                Map.entry(0x0c, new TimeSignatureInfo("2/4", 2, 4)),
                Map.entry(0x14, new TimeSignatureInfo("3/4", 3, 4)),
                Map.entry(0x1c, new TimeSignatureInfo("4/4", 4, 4)),
                Map.entry(0x24, new TimeSignatureInfo("5/4", 5, 4)),
                Map.entry(0x2c, new TimeSignatureInfo("6/4", 6, 4)),
                Map.entry(0x34, new TimeSignatureInfo("7/4", 7, 4)),
                Map.entry(0x3c, new TimeSignatureInfo("8/4", 8, 4)),
                Map.entry(0x44, new TimeSignatureInfo("9/4", 9, 4)),
                Map.entry(0x4c, new TimeSignatureInfo("10/4", 10, 4)),
                Map.entry(0x54, new TimeSignatureInfo("11/4", 11, 4)),
                Map.entry(0x5c, new TimeSignatureInfo("12/4", 12, 4)),
                Map.entry(0x02, new TimeSignatureInfo("1/8", 1, 8)),
                Map.entry(0x0a, new TimeSignatureInfo("2/8", 2, 8)),
                Map.entry(0x12, new TimeSignatureInfo("3/8", 3, 8)),
                Map.entry(0x1a, new TimeSignatureInfo("4/8", 4, 8)),
                Map.entry(0x22, new TimeSignatureInfo("5/8", 5, 8)),
                Map.entry(0x2a, new TimeSignatureInfo("6/8", 6, 8)),
                Map.entry(0x32, new TimeSignatureInfo("7/8", 7, 8)),
                Map.entry(0x3a, new TimeSignatureInfo("8/8", 8, 8)),
                Map.entry(0x42, new TimeSignatureInfo("9/8", 9, 8)),
                Map.entry(0x4a, new TimeSignatureInfo("10/8", 10, 8)),
                Map.entry(0x52, new TimeSignatureInfo("11/8", 11, 8)),
                Map.entry(0x5a, new TimeSignatureInfo("12/8", 12, 8)),
                Map.entry(0x06, new TimeSignatureInfo("1/2", 1, 2)),
                Map.entry(0x0e, new TimeSignatureInfo("2/2", 2, 2)),
                Map.entry(0x16, new TimeSignatureInfo("3/2", 3, 2)),
                Map.entry(0x1e, new TimeSignatureInfo("4/2", 4, 2)),
                Map.entry(0x01, new TimeSignatureInfo("1/16", 1, 16)),
                Map.entry(0x09, new TimeSignatureInfo("2/16", 2, 16)),
                Map.entry(0x11, new TimeSignatureInfo("3/16", 3, 16)),
                Map.entry(0x19, new TimeSignatureInfo("4/16", 4, 16)),
                Map.entry(0x21, new TimeSignatureInfo("5/16", 5, 16)),
                Map.entry(0x29, new TimeSignatureInfo("6/16", 6, 16)),
                Map.entry(0x31, new TimeSignatureInfo("7/16", 7, 16)),
                Map.entry(0x41, new TimeSignatureInfo("9/16", 9, 16)),
                Map.entry(0x59, new TimeSignatureInfo("12/16", 12, 16)),
                Map.entry(0x71, new TimeSignatureInfo("15/16", 15, 16)),
                Map.entry(0xa1, new TimeSignatureInfo("21/16", 21, 16)));
    }

    public static TimeSignatureInfo getTimeSignatureInfo(int sig) {
        TimeSignatureInfo info = TIME_SIGNATURE_MAP.get(sig);
        if (info == null) {
            info = new TimeSignatureInfo(String.format("unknown(0x%02X)", sig), 4, 4);
        }
        return info.withMetaMessage();
    }
}
