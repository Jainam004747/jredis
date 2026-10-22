package com.jainam.jredis.command;

/**
 * Server-level features that commands such as SAVE and INFO need. The command layer only sees
 * this small interface, not the whole server, which keeps the layers independent and lets
 * tests pass a simple stub.
 */
public interface ServerHooks {
    /** Writes a snapshot to disk. Returns an error message, or null on success. */
    String save();

    /** Compacts the append-only file. Returns an error message, or null on success. */
    String rewriteAof();

    /** Unix time (seconds) of the last successful snapshot, or 0. */
    long lastSave();

    /** The text returned by INFO. */
    String info(String section);

    /** CONFIG GET support: value of a setting, or null if unknown. */
    String config(String name);

    /** No-op hooks for tests and for replaying the AOF. */
    ServerHooks NONE = new ServerHooks() {
        @Override
        public String save() {
            return "ERR not available";
        }

        @Override
        public String rewriteAof() {
            return "ERR not available";
        }

        @Override
        public long lastSave() {
            return 0;
        }

        @Override
        public String info(String section) {
            return "";
        }

        @Override
        public String config(String name) {
            return null;
        }
    };
}
