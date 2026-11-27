package com.otrong.carcontrol.installer;

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
