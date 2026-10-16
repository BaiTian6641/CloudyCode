// Minimal RFC 6455 server-side helpers for gbcode-sessiond.
// Server role: never masks outbound frames; parses masked client frames.
"use strict";

const crypto = require("crypto");

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

function acceptKey(clientKey) {
  return crypto.createHash("sha1").update(clientKey + WS_GUID).digest("base64");
}

function encodeFrame(opcode, payload) {
  const n = payload.length;
  let header;
  if (n < 126) {
    header = Buffer.from([0x80 | opcode, n]);
  } else if (n <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(n, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeUInt32BE(Math.floor(n / 2 ** 32), 2);
    header.writeUInt32BE(n >>> 0, 6);
  }
  return Buffer.concat([header, payload]);
}
class FrameParser {
  constructor(onFrame, maxPayload) {
    this.buffer = Buffer.alloc(0);
    this.onFrame = onFrame;
    this.maxPayload = maxPayload || (1 << 20);
  }

  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (true) {
      if (this.buffer.length < 2) return;
      const b0 = this.buffer[0];
      const b1 = this.buffer[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let idx = 2;
      if (len === 126) {
        if (this.buffer.length < 4) return;
        len = this.buffer.readUInt16BE(2);
        idx = 4;
      } else if (len === 127) {
        if (this.buffer.length < 10) return;
        const hi = this.buffer.readUInt32BE(2);
        len = hi * 2 ** 32 + this.buffer.readUInt32BE(6);
        idx = 10;
      }
      if (len > this.maxPayload) {
        throw new Error("frame too large: " + len);
      }
      let mask = null;
      if (masked) {
        if (this.buffer.length < idx + 4) return;
        mask = this.buffer.subarray(idx, idx + 4);
        idx += 4;
      }
      if (this.buffer.length < idx + len) return;
      const payload = Buffer.from(this.buffer.subarray(idx, idx + len));
      if (mask) {
        for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      }
      this.buffer = this.buffer.subarray(idx + len);
      this.onFrame(opcode, payload);
    }
  }
}

module.exports = { acceptKey, encodeFrame, FrameParser, OPCODES: { TEXT: 0x1, BINARY: 0x2, CLOSE: 0x8, PING: 0x9, PONG: 0xa } };
