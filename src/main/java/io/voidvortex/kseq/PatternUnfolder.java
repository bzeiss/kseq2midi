package io.voidvortex.kseq;

import java.util.*;

import static io.voidvortex.kseq.KseqConstants.*;

/**
 * Resolves the pattern allocation table and pattern‑chain of a KSEQ file into
 * a list of byte‑ranges that form the linear playback order.  This class is
 * purely structural – it does not decode individual note/CC events.
 */
class PatternUnfolder {

    private final DebugSink dbg;

    private final byte[] data;
    private final int offsetShift;

    PatternUnfolder(byte[] data, int offsetShift, DebugSink dbg) {
        this.data = Objects.requireNonNull(data);
        this.offsetShift = offsetShift;
        this.dbg = dbg != null ? dbg : DebugSink.NOOP;
    }

    /**
     * Tuple class returned by {@link #unfold()}
     */
    record IntRange(int start, int end, int tickOffset) {
    }

    /**
     * Three‑byte pointer used throughout the pattern table
     */
    private record Pointer3B(int msb, int mid, int lsb) {
        int toOffset() {
            return (msb << 9) | (mid << 8) | lsb;
        }
    }

    public record PatternEntry(int bars, int timeSignature, Pointer3B start, Pointer3B end) {
        int ticksPerBar() {
            return switch (timeSignature) {
                case 0x1c -> 4 * MIDI_PPQN; // 4/4
                case 0x0c -> 2 * MIDI_PPQN; // 2/4
                case 0x14 -> 3 * MIDI_PPQN; // 3/4
                case 0x24 -> 5 * MIDI_PPQN; // 5/4
                case 0x2c -> 6 * MIDI_PPQN; // 6/4
                case 0x04 -> MIDI_PPQN; // 1/4
                case 0x02 -> MIDI_PPQN / 2; // 1/8
                case 0x0a -> MIDI_PPQN; // 2/8 = 1/4
                case 0x12 -> (int) (1.5 * MIDI_PPQN); // 3/8
                default -> 4 * MIDI_PPQN; // fallback to 4/4
            };
        }
    }

    /**
     * @return ordered list of pattern byte‑windows with their absolute tick
     * offset (converted to 96 PPQN already – 4 quarter notes per bar).
     */
    List<IntRange> unfold() {
        int chainOffset = PATTERN_CHAIN_OFFSET + offsetShift;
        dbg.log("Pattern chain area @" + Integer.toHexString(chainOffset) + ": ");
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 99; i++) {
            int v = data[chainOffset + i] & 0xFF;
            hex.append(String.format("%02X ", v));
        }
        dbg.log(hex.toString());

        // --- DEBUG: Print all F0 0F pattern markers in track data ---
        int trackStart = LINEAR_TRACK_DATA_OFFSET + offsetShift;
        int trackEnd = data.length;
        // Find the first pattern marker offset
        int firstPatternOffset = -1;
        for (int i = trackStart; i < trackEnd - 1; i++) {
            if ((data[i] & 0xFF) == 0xF0 && (data[i + 1] & 0xFF) == 0x0F) {
                firstPatternOffset = i;
                break;
            }
        }
        for (int i = trackStart; i < trackEnd - 1; i++) {
            if ((data[i] & 0xFF) == 0xF0 && (data[i + 1] & 0xFF) == 0x0F) {
                int relToTrackStart = i - trackStart;
                int relToFirstPattern = (firstPatternOffset != -1) ? i - firstPatternOffset : 0;
                dbg.log(String.format("Found pattern marker F0 0F at file offset 0x%X (%d), rel to track start: 0x%X (%d), rel to first pattern: 0x%X (%d)",
                    i, i, relToTrackStart, relToTrackStart, relToFirstPattern, relToFirstPattern));
            }
        }
        // --- END DEBUG ---

        List<PatternEntry> table = readPatternTable();
        dbg.log("Pattern table size: " + table.size());
        for (int i = 0; i < table.size(); i++) {
            PatternEntry p = table.get(i);
            if (p == null) {
                dbg.log("  Entry " + i + ": UNUSED");
            } else {
                int msb = p.start.msb;
                int mid = p.start.mid;
                int lsb = p.start.lsb;
                // Use new pointer calculation: (msb<<9) + (mid<<8) + lsb
                int computed = (msb << 9) + (mid << 8) + lsb;
                dbg.log(String.format("  Entry %d/%X: bars=%d, timeSig=0x%02X, start(msb=%02X mid=%02X lsb=%02X)=%d, end=%d (computed offset=%d)",
                        i, i, p.bars, p.timeSignature, msb, mid, lsb, computed, p.end.toOffset(), computed));
            }
        }
        List<IntRange> order = new ArrayList<>();
        Deque<RepeatFrame> repeat = new ArrayDeque<>();
        int chainPtr = PATTERN_CHAIN_OFFSET + offsetShift;
        int ticksSoFar = 0;
        int barsSoFar = 0;
        int barNum = 0;
        int chainPos = 0;
        while (chainPtr < LINEAR_TRACK_DATA_OFFSET + offsetShift) {
            int b = data[chainPtr] & 0xFF;
            dbg.log("chainPtr=0x" + Integer.toHexString(chainPtr) + " b=0x" + Integer.toHexString(b));
            if (b == 0xFF) break; // End marker
            if (b == 0x80) {
                // Begin repetition
                repeat.push(new RepeatFrame(chainPtr + 1, -1));
                chainPtr++;
                continue;
            }
            if (b >= 0x81 && b <= 0xE3) {
                int repeatCount = b - 0x80;
                if (!repeat.isEmpty()) {
                    RepeatFrame frame = repeat.peek();
                    if (frame.totalRepeats == 0 || frame.totalRepeats == -1) {
                        frame.totalRepeats = repeatCount + 1;
                    }
                    if (frame.incAndCheck()) {
                        chainPtr = frame.start;
                    } else {
                        repeat.pop();
                        chainPtr++;
                    }
                } else {
                    chainPtr++;
                }
                continue;
            }
            if (b >= 0x01 && b <= 0x99) {
                int idx = b - 1;
                dbg.log(String.format("Unrolling pattern idx %d for pattern chain position %d", idx, chainPos));
                dbg.log("Pattern chain at byte 0x" + Integer.toHexString(chainPtr) + ": pattern ref idx=" + idx + " (b=" + b + ") at bar=" + barNum);
                if (idx < table.size()) {
                    PatternEntry p = table.get(idx);
                    if (p != null) {
                        int msb = p.start.msb;
                        int mid = p.start.mid;
                        int lsb = p.start.lsb;
                        int relOffset = (msb << 9) + (mid << 8) + lsb;
                        int absStart = firstPatternOffset + relOffset;
                        int absEnd = absStart + (p.end.toOffset() - p.start.toOffset());
                        int tickOffset = ticksSoFar;
                        int patternByteLen = absEnd - absStart;
                        int expectedTicks = p.bars * p.ticksPerBar();
                        boolean isEmpty = (patternByteLen <= 0);
                        dbg.log(String.format("  Pattern idx=%d bars=%d timeSig=0x%02X byteLen=%d tickOffset=%d expectedTicks=%d EMPTY=%s BAR=%d", idx, p.bars, p.timeSignature, patternByteLen, tickOffset, expectedTicks, isEmpty, barNum));
                        order.add(new IntRange(absStart, absEnd, tickOffset));
                        dbg.log(String.format("Unrolled pattern idx=%d: absStart=0x%X absEnd=0x%X relOffset=0x%X tickOffset=%d barsSoFar=%d BAR=%d", idx, absStart, absEnd, relOffset, tickOffset, barsSoFar, barNum));
                        ticksSoFar += expectedTicks;
                        barNum += p.bars;
                        barsSoFar += p.bars;
                    } else {
                        dbg.log("  WARNING: pattern idx=" + idx + " is null in table at bar=" + barNum);
                    }
                } else {
                    dbg.log("  WARNING: pattern ref idx " + idx + " out of bounds (table size " + table.size() + ") at bar=" + barNum);
                }
            }
            chainPtr++;
            chainPos++;
        }
        dbg.log("PatternUnfolder.unfold() produced " + order.size() + " ranges.");
        return order;
    }

    // Utility: map time signature byte to [numerator, denominator-power]
    public static int[] timeSignatureToNumeratorDenominator(int timeSigByte) {
        return switch (timeSigByte) {
            case 0x1c -> new int[]{4, 2}; // 4/4
            case 0x0c -> new int[]{2, 2}; // 2/4
            case 0x14 -> new int[]{3, 2}; // 3/4
            case 0x24 -> new int[]{5, 2}; // 5/4
            case 0x2c -> new int[]{6, 2}; // 6/4
            case 0x04 -> new int[]{1, 2}; // 1/4
            case 0x02 -> new int[]{1, 3}; // 1/8
            case 0x0a -> new int[]{2, 3}; // 2/8
            case 0x12 -> new int[]{3, 3}; // 3/8
            default -> new int[]{4, 2}; // fallback 4/4
        };
    }

    // ────────────────────────── inner helpers ────────────────────────────

    private List<PatternEntry> readPatternTable() {
        List<PatternEntry> table = new ArrayList<>();
        int base = PATTERN_AREA_OFFSET + offsetShift;
        for (int i = 0, ofs = base; i < 99; i++, ofs += 9) {
            if ((data[ofs] & 0xFF) == 0) {
                table.add(null); // preserve index mapping for unused slots
                continue;
            }
            int timeSignature = data[ofs + 1] & 0xFF;
            int bars = data[ofs + 2] & 0xFF;
            Pointer3B start = new Pointer3B(data[ofs + 3] & 0xFF, data[ofs + 4] & 0xFF, data[ofs + 5] & 0xFF);
            Pointer3B end = new Pointer3B(data[ofs + 6] & 0xFF, data[ofs + 7] & 0xFF, data[ofs + 8] & 0xFF);
            table.add(new PatternEntry(bars, timeSignature, start, end));
        }
        return table;
    }

    private static class RepeatFrame {
        final int start;
        int count;
        int totalRepeats;
        RepeatFrame(int start, int repeatCount) {
            this.start = start;
            this.count = 0;
            this.totalRepeats = repeatCount == -1 ? 0 : repeatCount + 1;
        }
        boolean incAndCheck() {
            count++;
            return count < totalRepeats;
        }
    }
}
