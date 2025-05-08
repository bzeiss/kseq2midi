package io.voidvortex.kseq;

/**
 * Central location for every hard‑coded offset, byte marker and other “magic
 * numbers” that appear in the Yamaha SY‑77/99 KSEQ file format.  By funnelling
 * them all through this class, the rest of the code remains expressive and is
 * trivial to maintain if the spec ever changes.
 */
public final class KseqConstants {
    private KseqConstants() {
    }

    // ─────────────────────────── header ────────────────────────────────
    public static final String HEADER_ID_TEXT_KSEQ = "SY1_SEQALL";
    public static final String HEADER_ID_TEXT_ALLDATA = "SY1 ALL";
    public static final int HEADER_ID_OFFSET = 0x0000;
    public static final int HEADER_ID_LENGTH = 10;
    public static final int KSEQ_COMKSEQ_OFFSET = 0x400;
    public static final int ALLDATA_COMKSEQ_OFFSET = 0x800;
    public static final String COMKSEQ_MARKER = "COM-KSEQ";
    public static final int ALLDATA_OFFSET_SHIFT = 0x400;

    public static final int TEMPO_OFFSET = 0x041C;
    public static final int SONG_TITLE_OFFSET = 0x0422;
    public static final int SONG_TITLE_LENGTH = 8;

    public static final int TRACK_USAGE_LOW_OFFSET = 0x042A; // bits for tracks 1‑8
    public static final int TRACK_USAGE_HIGH_OFFSET = 0x042B; // bits for tracks 9‑16
    public static final int TRACK_CHANNELS_OFFSET = 0x042C; // 16 bytes, +track‑index

    // ─────────────────────── main data blocks ──────────────────────────
    /**
     * Linear (non‑pattern) track stream starts here.
     */
    public static final int LINEAR_TRACK_DATA_OFFSET = 0x1000;
    /**
     * First entry in the 99×9‑byte pattern allocation table.
     */
    public static final int PATTERN_AREA_OFFSET = 0x0826;
    /**
     * Start of the pattern‑chain definition area.
     */
    public static final int PATTERN_CHAIN_OFFSET = 0x0BA1;

    // ───────────────────────── marker bytes ────────────────────────────
    public static final int MARKER_TRACK_START = 0xF0;
    public static final int MARKER_TRACK_END = 0xF2;
    public static final int MARKER_SEQUENCE_END = 0xF1;

    // Special case: pattern start is 0xF0 0x0F; first byte is same as track‑start

    // ──────────────────────────── MIDI ─────────────────────────────────
    public static final int VIRTUAL_PATTERN_TRACK = 16;   // where we dump unfolded patterns
    public static final int MIDI_PPQN = 96;
}
