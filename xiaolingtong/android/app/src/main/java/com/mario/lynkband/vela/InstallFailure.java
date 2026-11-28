// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband.vela;

import java.io.IOException;

/** An installation error whose message can be shown to the user. */
class InstallFailure extends IOException {
    InstallFailure(String message) {
        super(message);
    }

    InstallFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
