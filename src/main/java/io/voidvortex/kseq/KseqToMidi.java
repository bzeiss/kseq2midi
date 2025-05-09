package io.voidvortex.kseq;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.voidvortex.kseq.KseqConstants.*;
import static io.voidvortex.kseq.MidiUtil.*;

/**
 * Entry‑point utility that converts a Yamaha KSEQ file into an SMF‑1 file.
 */
public final class KseqToMidi {

    private KseqToMidi() {
    }

    // ───────────────────────── public API ────────────────────────────────

    public record FileTypeInfo(boolean isAllData, int offsetShift) {
    }

    private static FileTypeInfo fileTypeInfo;

    public static void convert(Path kseq, boolean debugFlag) throws IOException, javax.sound.midi.InvalidMidiDataException {
        byte[] data = Files.readAllBytes(kseq);
        fileTypeInfo = validateHeader(data);
        System.out.println("Detected file type: " + (fileTypeInfo.isAllData ? "AllData" : "KSEQ"));
        Map<Integer, List<javax.sound.midi.MidiEvent>> midi = new HashMap<>();
        Map<Integer, Integer> midiChannel = new HashMap<>();
        readTrackHeaderInfo(data, midi, midiChannel);

        // Read initial global tempo from header
        int initialGlobalTempoBpm = data[getOffset(TEMPO_OFFSET)] & 0xFF;

        DebugSink sink = debugFlag ? System.out::println : DebugSink.NOOP;

        // --- Read time signature table and output changes ---
        int tableOffset = getOffset(KseqConstants.TIME_SIG_TABLE_OFFSET);
        if (data.length >= tableOffset + KseqConstants.TIME_SIG_TABLE_LENGTH) {
            byte[] timeSigTable = Arrays.copyOfRange(data, tableOffset, tableOffset + KseqConstants.TIME_SIG_TABLE_LENGTH);
            int lastSig = -1;
            for (int bar = 0; bar < timeSigTable.length; ++bar) {
                int sig = timeSigTable[bar] & 0xFF;
                if (bar == 0 || sig != lastSig) {
                    sink.log(String.format("[DEBUG] Time signature change at bar %d: %s (0x%02X)", bar + 1, decodeTimeSignature(sig), sig));
                }
                lastSig = sig;
            }
        } else {
            sink.log("[DEBUG] Time signature table not present or file too short.");
        }

        TrackParser parser = new TrackParser(data, midi, midiChannel, sink, fileTypeInfo.offsetShift, initialGlobalTempoBpm);
        parser.parseLinearTracks();

        int patternMarker = findFirstPatternMarker(data);
        if (patternMarker != -1) {
            PatternUnfolder unfolder = new PatternUnfolder(data, fileTypeInfo.offsetShift, sink);
            List<PatternUnfolder.IntRange> unfolded = unfolder.unfold();
            for (PatternUnfolder.IntRange r : unfolded) {
                parser.parse(r.start(), r.end(), r.tickOffset(), VIRTUAL_PATTERN_TRACK);
            }
        }

        Sequence seq = new Sequence(Sequence.PPQ, MIDI_PPQN);

        // Set initial time signature at bar 0 as a MIDI meta event at tick 0
        int tsTableOffset = getOffset(KseqConstants.TIME_SIG_TABLE_OFFSET);
        int tsByte = 0x1c; // default 4/4 if not found
        if (data.length > tsTableOffset) {
            tsByte = data[tsTableOffset] & 0xFF;
        }
        javax.sound.midi.MetaMessage timeSigMsg = createTimeSignatureMeta(tsByte);
        seq.createTrack().add(new javax.sound.midi.MidiEvent(timeSigMsg, 0));
        sink.log(String.format("[DEBUG] Wrote MIDI time signature meta event at tick 0: %s (0x%02X) raw=%s",
            decodeTimeSignature(tsByte), tsByte, Arrays.toString(timeSigMsg.getData())));

        buildTempoTrack(seq, data);
        buildSongTracks(seq, midi);

        Path out = kseq.resolveSibling(kseq.getFileName() + ".mid");
        javax.sound.midi.MidiSystem.write(seq, 1, out.toFile());
        if (debugFlag)
            System.out.println("Written " + out);
    }

    /**
     * Decodes a KSEQ time signature byte to a human-readable string like "4/4", "3/8", etc.
     */
    private static String decodeTimeSignature(int sig) {
        // Denominator mapping: lower nibble indicates denominator
        // 0x0x = /4, 0xAx = /8, 0xEx = /2, 0x9x = /16 (see kseq.bt)
        // But in kseq.bt, the encoding is explicit per value, so safest is a lookup
        switch (sig) {
            case 0x04: return "1/4";
            case 0x0c: return "2/4";
            case 0x14: return "3/4";
            case 0x1c: return "4/4";
            case 0x24: return "5/4";
            case 0x2c: return "6/4";
            case 0x34: return "7/4";
            case 0x3c: return "8/4";
            case 0x44: return "9/4";
            case 0x4c: return "10/4";
            case 0x54: return "11/4";
            case 0x5c: return "12/4";
            case 0x02: return "1/8";
            case 0x0a: return "2/8";
            case 0x12: return "3/8";
            case 0x1a: return "4/8";
            case 0x22: return "5/8";
            case 0x2a: return "6/8";
            case 0x32: return "7/8";
            case 0x3a: return "8/8";
            case 0x42: return "9/8";
            case 0x4a: return "10/8";
            case 0x52: return "11/8";
            case 0x5a: return "12/8";
            case 0x06: return "1/2";
            case 0x0e: return "2/2";
            case 0x16: return "3/2";
            case 0x1e: return "4/2";
            case 0x01: return "1/16";
            case 0x09: return "2/16";
            case 0x11: return "3/16";
            case 0x19: return "4/16";
            case 0x21: return "5/16";
            case 0x29: return "6/16";
            case 0x31: return "7/16";
            case 0x41: return "9/16";
            case 0x59: return "12/16";
            case 0x71: return "15/16";
            case 0xa1: return "21/16";
            default: return String.format("unknown(0x%02X)", sig);
        }
    }

    // ─────────────────────── SMF helpers ─────────────────────────────────---

    /**
     * Creates a MIDI time signature MetaMessage from a KSEQ time signature byte.
     * Supports only standard signatures (e.g., 4/4, 3/4, etc.).
     */
    private static MetaMessage createTimeSignatureMeta(int sig) {
        int numerator, denominator;
        switch (sig) {
            case 0x04: numerator = 1; denominator = 4; break;
            case 0x0c: numerator = 2; denominator = 4; break;
            case 0x14: numerator = 3; denominator = 4; break;
            case 0x1c: numerator = 4; denominator = 4; break;
            case 0x24: numerator = 5; denominator = 4; break;
            case 0x2c: numerator = 6; denominator = 4; break;
            case 0x34: numerator = 7; denominator = 4; break;
            case 0x3c: numerator = 8; denominator = 4; break;
            case 0x44: numerator = 9; denominator = 4; break;
            case 0x4c: numerator = 10; denominator = 4; break;
            case 0x54: numerator = 11; denominator = 4; break;
            case 0x5c: numerator = 12; denominator = 4; break;
            case 0x02: numerator = 1; denominator = 8; break;
            case 0x0a: numerator = 2; denominator = 8; break;
            case 0x12: numerator = 3; denominator = 8; break;
            case 0x1a: numerator = 4; denominator = 8; break;
            case 0x22: numerator = 5; denominator = 8; break;
            case 0x2a: numerator = 6; denominator = 8; break;
            case 0x32: numerator = 7; denominator = 8; break;
            case 0x3a: numerator = 8; denominator = 8; break;
            case 0x42: numerator = 9; denominator = 8; break;
            case 0x4a: numerator = 10; denominator = 8; break;
            case 0x52: numerator = 11; denominator = 8; break;
            case 0x5a: numerator = 12; denominator = 8; break;
            case 0x06: numerator = 1; denominator = 2; break;
            case 0x0e: numerator = 2; denominator = 2; break;
            case 0x16: numerator = 3; denominator = 2; break;
            case 0x1e: numerator = 4; denominator = 2; break;
            case 0x01: numerator = 1; denominator = 16; break;
            case 0x09: numerator = 2; denominator = 16; break;
            case 0x11: numerator = 3; denominator = 16; break;
            case 0x19: numerator = 4; denominator = 16; break;
            case 0x21: numerator = 5; denominator = 16; break;
            case 0x29: numerator = 6; denominator = 16; break;
            case 0x31: numerator = 7; denominator = 16; break;
            case 0x41: numerator = 9; denominator = 16; break;
            case 0x59: numerator = 12; denominator = 16; break;
            case 0x71: numerator = 15; denominator = 16; break;
            case 0xa1: numerator = 21; denominator = 16; break;
            default: numerator = 4; denominator = 4; break; // fallback to 4/4
        }
        int midiDenom = 0;
        int d = denominator;
        while (d > 1) {
            d >>= 1;
            midiDenom++;
        }
        byte[] data = new byte[] {
            (byte)numerator,
            (byte)midiDenom,
            24, // MIDI Clocks per metronome click (default: 24 = quarter note)
            8   // 32nd notes per 24 MIDI clocks (default: 8)
        };
        try {
            return new MetaMessage(0x58, data, 4);
        } catch (javax.sound.midi.InvalidMidiDataException e) {
            throw new IllegalStateException(e);
        }
    }


    private static void buildTempoTrack(Sequence seq, byte[] data) {
        Track t = seq.createTrack();
        t.add(new javax.sound.midi.MidiEvent(tempoMeta(data[getOffset(TEMPO_OFFSET)] & 0xFF), 0));
        t.add(new javax.sound.midi.MidiEvent(textMeta(0x03, new String(data, getOffset(SONG_TITLE_OFFSET), SONG_TITLE_LENGTH).trim()), 0));
    }

    private static void buildSongTracks(Sequence seq, Map<Integer, List<javax.sound.midi.MidiEvent>> midi) {
        midi.values().forEach(events -> {
            Track tr = seq.createTrack();
            events.stream().sorted(Comparator.comparingLong(javax.sound.midi.MidiEvent::getTick)).forEach(tr::add);
            try {
                tr.add(new javax.sound.midi.MidiEvent(new MetaMessage(0x2F, new byte[0], 0), tr.ticks()));
            } catch (Exception ignored) {
            }
        });
    }

    // ──────────────────── header / misc helpers ─────────────────────────----

    private static FileTypeInfo validateHeader(byte[] d) {
        String header = new String(d, HEADER_ID_OFFSET, HEADER_ID_LENGTH).trim();
        boolean allData;
        int shift;
        if (HEADER_ID_TEXT_KSEQ.equals(header)) {
            allData = false;
            shift = 0;
            if (!checkComKseq(d, KSEQ_COMKSEQ_OFFSET)) {
                int found = scanForComKseq(d);
                if (found == -1)
                    throw new IllegalArgumentException("COM-KSEQ marker not found in KSEQ file");
                shift = found - KSEQ_COMKSEQ_OFFSET;
            }
        } else if (HEADER_ID_TEXT_ALLDATA.equals(header)) {
            allData = true;
            shift = ALLDATA_OFFSET_SHIFT;
            if (!checkComKseq(d, ALLDATA_COMKSEQ_OFFSET)) {
                int found = scanForComKseq(d);
                if (found == -1)
                    throw new IllegalArgumentException("COM-KSEQ marker not found in AllData file");
                shift = found - KSEQ_COMKSEQ_OFFSET;
            }
        } else {
            throw new IllegalArgumentException("Not a KSEQ or AllData file");
        }
        return new FileTypeInfo(allData, shift);
    }

    @SuppressWarnings("All")
    private static boolean checkComKseq(byte[] d, int offset) {
        if (d.length < offset + COMKSEQ_MARKER.length())
            return false;

        String marker = new String(d, offset, COMKSEQ_MARKER.length());
        return COMKSEQ_MARKER.equals(marker);
    }

    private static int scanForComKseq(byte[] d) {
        byte[] markerBytes = COMKSEQ_MARKER.getBytes();
        outer:
        for (int i = 0; i <= d.length - markerBytes.length; i++) {
            for (int j = 0; j < markerBytes.length; j++) {
                if (d[i + j] != markerBytes[j])
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int getOffset(int base) {
        return base + (fileTypeInfo != null ? fileTypeInfo.offsetShift : 0);
    }

    private static int findFirstPatternMarker(byte[] d) {
        for (int i = getOffset(LINEAR_TRACK_DATA_OFFSET); i < d.length - 1; i++)
            if ((d[i] & 0xFF) == 0xF0 && (d[i + 1] & 0xFF) == 0x0F) return i;
        return -1;
    }

    private static void readTrackHeaderInfo(byte[] d, Map<Integer, List<javax.sound.midi.MidiEvent>> midi, Map<Integer, Integer> ch) {
        int lo = d[getOffset(TRACK_USAGE_LOW_OFFSET)] & 0xFF, hi = d[getOffset(TRACK_USAGE_HIGH_OFFSET)] & 0xFF;
        for (int i = 0; i < 16; i++) {
            if (((i < 8 ? lo : hi) & (1 << (i & 7))) == 0) continue;
            int tr = i + 1;
            midi.putIfAbsent(tr, new ArrayList<>());
            ch.put(tr, (d[getOffset(TRACK_CHANNELS_OFFSET) + i] & 0xFF) + 1);
        }
    }

    // ───────────────────────── CLI ─────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: kseq2midi [--debug] <file.kseq/all>");
            return;
        }
        boolean debug = Objects.equals(args[0], "--debug");
        Path file = Path.of(debug ? args[1] : args[0]);
        convert(file, debug);
    }
}
