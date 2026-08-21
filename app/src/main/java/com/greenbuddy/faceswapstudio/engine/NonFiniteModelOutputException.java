package com.greenbuddy.faceswapstudio.engine;

/** Signals numerical failure that can be retried with the conservative CPU backend. */
final class NonFiniteModelOutputException extends FaceSwapException {
    NonFiniteModelOutputException() {
        super("Das beschleunigte KI-Modell lieferte ungültige Zahlenwerte.");
    }
}
