package com.mario.lynkband.vela;

import java.util.logging.Logger;

/** Log stage names only. Pairing credentials and device payloads stay out of diagnostics. */
final class BandDiagnostics {
    static final class Trace {
        static final Trace OFF = new Trace();
        private static final Logger LOG = Logger.getLogger("VelaInstall");
        void event(String name, Object... fields) { LOG.fine(name); }
        void failure(String name, Throwable error, Object... fields) { LOG.fine(name); }
    }
}
