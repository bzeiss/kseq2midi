package io.voidvortex.kseq;

/**  tiny lambda target – avoids sprinkling `if(debug)` all over the code   */
@FunctionalInterface
public interface DebugSink {
    void log(String msg);

    DebugSink NOOP = _ -> { /* nothing */ };
}
