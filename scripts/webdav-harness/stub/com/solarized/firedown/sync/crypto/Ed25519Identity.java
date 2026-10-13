package com.solarized.firedown.sync.crypto;

/** Stand-in for the BouncyCastle-backed signer: SyncIdentity builds one, the
 *  backup path never signs anything (only bookmark sync does). */
public final class Ed25519Identity {
    private final byte[] publicKey;

    public Ed25519Identity(byte[] seed) {
        this.publicKey = seed.clone();
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] sign(byte[] message) {
        return new byte[64];
    }

    public static boolean verify(byte[] publicKey, byte[] message, byte[] sig) {
        return false;
    }
}
