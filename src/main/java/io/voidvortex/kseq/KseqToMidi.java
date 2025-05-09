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
                    TimeSignatureInfo tsInfo = getTimeSignatureInfo(sig);
                sink.log(String.format("[DEBUG] Time signature change at bar %d: %s (0x%02X)", bar + 1, tsInfo.humanReadable, sig));
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

        Sequence seq = new Sequence(Sequence.PPQ, KseqConstants.MIDI_PPQN);

        // Set all time signature changes as MIDI meta events at the correct ticks

        int tsTableOffset = getOffset(KseqConstants.TIME_SIG_TABLE_OFFSET);
        int tsTableLen = KseqConstants.TIME_SIG_TABLE_LENGTH;
        byte[] timeSigTable = null;
        if (data.length >= tsTableOffset + tsTableLen) {
            timeSigTable = Arrays.copyOfRange(data, tsTableOffset, tsTableOffset + tsTableLen);
        }
        Track tsTrack = seq.createTrack();
        int lastSig = -1;
        int tick = 0;
        TimeSignatureInfo tsInfo = getTimeSignatureInfo(0x1c); // default 4/4
        for (int bar = 0; bar < (timeSigTable != null ? timeSigTable.length : 1); ++bar) {
            int sig = timeSigTable != null ? (timeSigTable[bar] & 0xFF) : 0x1c; // default 4/4
            if (bar == 0 || sig != lastSig) {
                tsInfo = getTimeSignatureInfo(sig);
                tsTrack.add(new javax.sound.midi.MidiEvent(tsInfo.metaMessage, tick));
                sink.log(String.format("[DEBUG] Wrote MIDI time signature meta event at tick %d: %s (0x%02X) raw=%s",
                    tick, tsInfo.humanReadable, sig, Arrays.toString(tsInfo.metaMessage.getData())));
            }
            lastSig = sig;
            // Calculate ticks for this bar using current numerator/denominator
            int quarterNotesPerBar = tsInfo.numerator * 4 / tsInfo.denominator; // e.g., 4/4 = 4, 3/4 = 3, 6/8 = 3
            int ticksPerBar = quarterNotesPerBar * KseqConstants.MIDI_PPQN;
            tick += ticksPerBar;
        }

        buildTempoTrack(seq, data);
        buildSongTracks(seq, midi);

        Path out = kseq.resolveSibling(kseq.getFileName() + ".mid");
        javax.sound.midi.MidiSystem.write(seq, 1, out.toFile());
        if (debugFlag)
            System.out.println("Written " + out);
    }

    /**
     * Given a KSEQ time signature byte, returns an object containing:
     * - human-readable string (e.g., "4/4")
     * - numerator
     * - denominator
     * - MetaMessage (MIDI time signature event)
     */
    private static TimeSignatureInfo getTimeSignatureInfo(int sig) {
        int numerator, denominator;
        String human;
        switch (sig) {
            case 0x04: numerator = 1; denominator = 4; human = "1/4"; break;
            case 0x0c: numerator = 2; denominator = 4; human = "2/4"; break;
            case 0x14: numerator = 3; denominator = 4; human = "3/4"; break;
            case 0x1c: numerator = 4; denominator = 4; human = "4/4"; break;
            case 0x24: numerator = 5; denominator = 4; human = "5/4"; break;
            case 0x2c: numerator = 6; denominator = 4; human = "6/4"; break;
            case 0x34: numerator = 7; denominator = 4; human = "7/4"; break;
            case 0x3c: numerator = 8; denominator = 4; human = "8/4"; break;
            case 0x44: numerator = 9; denominator = 4; human = "9/4"; break;
            case 0x4c: numerator = 10; denominator = 4; human = "10/4"; break;
            case 0x54: numerator = 11; denominator = 4; human = "11/4"; break;
            case 0x5c: numerator = 12; denominator = 4; human = "12/4"; break;
            case 0x02: numerator = 1; denominator = 8; human = "1/8"; break;
            case 0x0a: numerator = 2; denominator = 8; human = "2/8"; break;
            case 0x12: numerator = 3; denominator = 8; human = "3/8"; break;
            case 0x1a: numerator = 4; denominator = 8; human = "4/8"; break;
            case 0x22: numerator = 5; denominator = 8; human = "5/8"; break;
            case 0x2a: numerator = 6; denominator = 8; human = "6/8"; break;
            case 0x32: numerator = 7; denominator = 8; human = "7/8"; break;
            case 0x3a: numerator = 8; denominator = 8; human = "8/8"; break;
            case 0x42: numerator = 9; denominator = 8; human = "9/8"; break;
            case 0x4a: numerator = 10; denominator = 8; human = "10/8"; break;
            case 0x52: numerator = 11; denominator = 8; human = "11/8"; break;
            case 0x5a: numerator = 12; denominator = 8; human = "12/8"; break;
            case 0x06: numerator = 1; denominator = 2; human = "1/2"; break;
            case 0x0e: numerator = 2; denominator = 2; human = "2/2"; break;
            case 0x16: numerator = 3; denominator = 2; human = "3/2"; break;
            case 0x1e: numerator = 4; denominator = 2; human = "4/2"; break;
            case 0x01: numerator = 1; denominator = 16; human = "1/16"; break;
            case 0x09: numerator = 2; denominator = 16; human = "2/16"; break;
            case 0x11: numerator = 3; denominator = 16; human = "3/16"; break;
            case 0x19: numerator = 4; denominator = 16; human = "4/16"; break;
            case 0x21: numerator = 5; denominator = 16; human = "5/16"; break;
            case 0x29: numerator = 6; denominator = 16; human = "6/16"; break;
            case 0x31: numerator = 7; denominator = 16; human = "7/16"; break;
            case 0x41: numerator = 9; denominator = 16; human = "9/16"; break;
            case 0x59: numerator = 12; denominator = 16; human = "12/16"; break;
            case 0x71: numerator = 15; denominator = 16; human = "15/16"; break;
            case 0xa1: numerator = 21; denominator = 16; human = "21/16"; break;
            default: numerator = 4; denominator = 4; human = String.format("unknown(0x%02X)", sig); break;
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
        MetaMessage msg;
        try {
            msg = new MetaMessage(0x58, data, 4);
        } catch (javax.sound.midi.InvalidMidiDataException e) {
            throw new IllegalStateException(e);
        }
        return new TimeSignatureInfo(human, numerator, denominator, msg);
    }

    private static class TimeSignatureInfo {
        final String humanReadable;
        final int numerator;
        final int denominator;
        final MetaMessage metaMessage;
        TimeSignatureInfo(String humanReadable, int numerator, int denominator, MetaMessage metaMessage) {
            this.humanReadable = humanReadable;
            this.numerator = numerator;
            this.denominator = denominator;
            this.metaMessage = metaMessage;
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
