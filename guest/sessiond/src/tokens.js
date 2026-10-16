// Session token issuance/verification: v1.<payload64>.<sig64> HMAC tokens.
"use strict";

const crypto = require("crypto");

class TokenIssuer {
  constructor(secret) {
    this.secret = secret || crypto.randomBytes(32);
  }

  issue(appHash, ttlMs) {
    const now = Date.now();
    const payload = Buffer.from(JSON.stringify({
      app: appHash,
      iat: now,
      exp: now + ttlMs,
      sid: crypto.randomBytes(12).toString("base64url"),
    })).toString("base64url");
    const sig = crypto.createHmac("sha256", this.secret).update(payload).digest("base64url");
    return { token: `v1.${payload}.${sig}`, expiresAt: now + ttlMs };
  }

  /** Returns payload object or null (bad format/signature/expired). */
  verify(token) {
    if (typeof token !== "string") return null;
    const parts = token.split(".");
    if (parts.length !== 3 || parts[0] !== "v1") return null;
    const [/* v1 */, payload, sig] = parts;
    const expected = crypto.createHmac("sha256", this.secret).update(payload).digest();
    const given = Buffer.from(sig, "base64url");
    if (expected.length !== given.length || !crypto.timingSafeEqual(expected, given)) return null;
    let data;
    try {
      data = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    } catch {
      return null;
    }
    if (typeof data.exp !== "number" || Date.now() > data.exp) return null;
    return data;
  }
}

module.exports = { TokenIssuer };
