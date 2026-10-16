package dev.gbcode.app.connection;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal Ed25519 (RFC 8032) using BigInteger arithmetic.
 * Deliberately boring and auditable: key generation from seed, sign, verify.
 * Performance is irrelevant here (a few signatures per pairing session).
 */
final class Ed25519 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);

    /** Affine point on the Edwards curve. */
    private static final class Point {
        final BigInteger x;
        final BigInteger y;

        Point(BigInteger x, BigInteger y) {
            this.x = x.mod(P);
            this.y = y.mod(P);
        }

        Point add(Point o) {
            BigInteger x1x2 = x.multiply(o.x).mod(P);
            BigInteger y1y2 = y.multiply(o.y).mod(P);
            BigInteger x1y2 = x.multiply(o.y).mod(P);
            BigInteger y1x2 = y.multiply(o.x).mod(P);
            BigInteger dxxyy = D.multiply(x1x2).multiply(y1y2).mod(P);
            BigInteger x3 = x1y2.add(y1x2).mod(P)
                    .multiply(BigInteger.ONE.add(dxxyy).mod(P).modInverse(P)).mod(P);
            // for a = -1: y3 = (y1y2 + x1x2) / (1 - d*x1x2*y1y2)
            BigInteger y3 = y1y2.add(x1x2).mod(P)
                    .multiply(BigInteger.ONE.subtract(dxxyy).mod(P).modInverse(P)).mod(P);
            return new Point(x3, y3);
        }

        Point mul(BigInteger scalar) {
            Point result = new Point(BigInteger.ZERO, BigInteger.ONE); // identity
            Point addend = this;
            BigInteger k = scalar;
            while (k.signum() > 0) {
                if (k.testBit(0)) result = result.add(addend);
                addend = addend.add(addend);
                k = k.shiftRight(1);
            }
            return result;
        }

        byte[] encode() {
            byte[] out = littleEndian(y, 32);
            if (x.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int srcPos = Math.max(0, raw.length - len);
        int copyLen = Math.min(raw.length, len);
        for (int i = 0; i < copyLen; i++) {
            out[i] = raw[raw.length - 1 - i];
        }
        return out;
    }

    private static BigInteger littleEndianToInt(byte[] b) {
        byte[] reversed = new byte[b.length];
        for (int i = 0; i < b.length; i++) reversed[i] = b[b.length - 1 - i];
        return new BigInteger(1, reversed);
    }

    private static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] part : parts) md.update(part);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Point basePoint() {
        BigInteger y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger x = u.multiply(v.modInverse(P)).mod(P)
                .modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P);
        if (!x.multiply(x).subtract(u.multiply(v.modInverse(P))).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.testBit(0)) x = P.subtract(x);
        return new Point(x, y);
    }

    static final class KeyPair {
        final byte[] publicKey; // 32 bytes
        final byte[] privateKey; // 32-byte seed

        KeyPair(byte[] publicKey, byte[] privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }
    }

    static KeyPair generate() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return fromSeed(seed);
    }

    static KeyPair fromSeed(byte[] seed) {
        byte[] h = sha512(seed);
        byte[] aBytes = h.clone();
        aBytes[0] &= (byte) 248;
        aBytes[31] &= (byte) 63;
        aBytes[31] |= (byte) 64;
        BigInteger a = littleEndianToInt(java.util.Arrays.copyOf(aBytes, 32));
        Point A = basePoint().mul(a);
        return new KeyPair(A.encode(), seed.clone());
    }

    static byte[] sign(byte[] privateSeed, byte[] publicKey, byte[] message) {
        byte[] h = sha512(privateSeed);
        byte[] aBytes = h.clone();
        aBytes[0] &= (byte) 248;
        aBytes[31] &= (byte) 63;
        aBytes[31] |= (byte) 64;
        BigInteger a = littleEndianToInt(java.util.Arrays.copyOf(aBytes, 32));
        byte[] prefix = java.util.Arrays.copyOfRange(h, 32, 64);

        BigInteger r = littleEndianToInt(sha512(prefix, message)).mod(L);
        Point R = basePoint().mul(r);
        byte[] rEnc = R.encode();

        BigInteger k = littleEndianToInt(sha512(rEnc, publicKey, message)).mod(L);
        BigInteger S = r.add(k.multiply(a)).mod(L);

        byte[] sig = new byte[64];
        System.arraycopy(rEnc, 0, sig, 0, 32);
        System.arraycopy(littleEndian(S, 32), 0, sig, 32, 32);
        return sig;
    }

    /** Verify an RFC 8032 signature (used by tests; the daemon verifies too). */
    static boolean verify(byte[] publicKey, byte[] signature, byte[] message) {
        if (signature.length != 64) return false;
        byte[] rEnc = java.util.Arrays.copyOf(signature, 32);
        BigInteger S = littleEndianToInt(java.util.Arrays.copyOfRange(signature, 32, 64));
        if (S.compareTo(L) >= 0) return false;

        Point A;
        try {
            A = decode(publicKey);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Point R;
        try {
            R = decode(rEnc);
        } catch (IllegalArgumentException e) {
            return false;
        }
        BigInteger k = littleEndianToInt(sha512(rEnc, publicKey, message)).mod(L);
        // S*B == R + k*A
        Point left = basePoint().mul(S);
        Point right = R.add(A.mul(k));
        return java.util.Arrays.equals(left.encode(), right.encode());
    }

    private static Point decode(byte[] enc) {
        byte[] yBytes = enc.clone();
        boolean xOdd = (yBytes[31] & 0x80) != 0;
        yBytes[31] &= (byte) 0x7f;
        BigInteger y = littleEndianToInt(yBytes);
        if (y.compareTo(P) >= 0) throw new IllegalArgumentException("y out of range");
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger vInv = v.modInverse(P);
        BigInteger x = u.multiply(vInv).mod(P)
                .modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P);
        if (!x.multiply(x).subtract(u.multiply(vInv)).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (!x.multiply(x).subtract(u.multiply(vInv)).mod(P).equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("not on curve");
        }
        if (x.testBit(0) != xOdd) x = P.subtract(x);
        return new Point(x, y);
    }
}
