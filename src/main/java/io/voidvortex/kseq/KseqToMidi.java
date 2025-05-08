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
        DebugSink sink = debugFlag ? System.out::println : DebugSink.NOOP;
        TrackParser parser = new TrackParser(data, midi, midiChannel, sink, fileTypeInfo.offsetShift);
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
        buildTempoTrack(seq, data);
        buildSongTracks(seq, midi);

        Path out = kseq.resolveSibling(kseq.getFileName() + ".mid");
        javax.sound.midi.MidiSystem.write(seq, 1, out.toFile());
        if (debugFlag)
            System.out.println("Written " + out);
    }

    // ─────────────────────── SMF helpers ─────────────────────────────────---

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
