// S05.01 device registry, incarnations and exclusive leases.
// Registry logic is real; hardware enumeration providers (S05.02/.03, U01)
// attach later through the same interface. No device here is a hardware claim.
"use strict";

const crypto = require("crypto");

const LEASE_TTL_MS = 60_000;

class DeviceRegistry {
  constructor() {
    this.devices = new Map(); // deviceId -> record
    this.leases = new Map();  // deviceId -> lease
  }

  /**
   * Register or refresh an attachment. A new attachmentEvent increments the
   * incarnation; old leases for prior incarnations are invalidated.
   * attachmentEvent must be unique per physical attach/detach cycle from the
   * (future) provider; in tests we supply it directly.
   */
  attach({ attachmentEvent, vid, pid, interfaces = [], serial = null, label = null }) {
    if (!attachmentEvent) throw new Error("attachmentEvent required");
    const existing = [...this.devices.values()].find((d) => d.attachmentEvent === attachmentEvent);
    if (existing) return publicView(existing);

    const deviceId = "dev_" + crypto.randomBytes(8).toString("base64url");
    const incarnation = "inc_" + crypto.randomBytes(8).toString("base64url");
    const record = {
      deviceId,
      incarnation,
      attachmentEvent,
      vid: vid ?? null,
      pid: pid ?? null,
      interfaces,
      serial,
      label,
      attachedAt: new Date().toISOString(),
    };
    this.devices.set(deviceId, record);
    return publicView(record);
  }

  detach(deviceId) {
    const record = this.devices.get(deviceId);
    if (!record) return false;
    this.leases.delete(deviceId); // lease dies with the attachment
    this.devices.delete(deviceId);
    return true;
  }

  list() {
    return [...this.devices.values()].map((d) => ({
      ...publicView(d),
      lease: this.leases.has(d.deviceId) ? leaseView(this.leases.get(d.deviceId)) : null,
    }));
  }

  /**
   * Exclusive lease bound to (deviceId, incarnation, sessionSid).
   * Stale incarnations are rejected (the caller saw an old attachment).
   */
  requestLease({ deviceId, incarnation, sessionSid, ttlMs = LEASE_TTL_MS }) {
    const record = this.devices.get(deviceId);
    if (!record) throw leaseError("device_unknown", deviceId);
    if (record.incarnation !== incarnation) {
      throw leaseError("stale_incarnation", "expected " + record.incarnation);
    }
    const now = Date.now();
    const current = this.leases.get(deviceId);
    if (current && current.expiresAt > now) {
      if (current.sessionSid === sessionSid) {
        current.expiresAt = now + ttlMs; // renew own lease
        return leaseView(current);
      }
      throw leaseError("device_busy", "leased by another session until " + current.expiresAt);
    }
    const lease = {
      leaseId: "lease_" + crypto.randomBytes(8).toString("base64url"),
      deviceId,
      incarnation,
      sessionSid,
      acquiredAt: now,
      expiresAt: now + ttlMs,
    };
    this.leases.set(deviceId, lease);
    return leaseView(lease);
  }

  releaseLease({ deviceId, leaseId, sessionSid }) {
    const current = this.leases.get(deviceId);
    if (!current) return { released: false, reason: "no_active_lease" };
    if (current.leaseId !== leaseId) throw leaseError("lease_mismatch", leaseId);
    if (current.sessionSid !== sessionSid) throw leaseError("not_lease_owner", leaseId);
    this.leases.delete(deviceId);
    return { released: true };
  }

  /** Test helper: force-expire a lease (time travel). */
  _expire(deviceId) {
    const current = this.leases.get(deviceId);
    if (current) current.expiresAt = Date.now() - 1;
  }

  sweepExpired() {
    const now = Date.now();
    for (const [deviceId, lease] of this.leases) {
      if (lease.expiresAt <= now) this.leases.delete(deviceId);
    }
  }
}

function publicView(record) {
  return {
    deviceId: record.deviceId,
    incarnation: record.incarnation,
    vid: record.vid,
    pid: record.pid,
    interfaces: record.interfaces,
    serial: record.serial,
    label: record.label,
    attachedAt: record.attachedAt,
  };
}

function leaseView(lease) {
  return {
    leaseId: lease.leaseId,
    deviceId: lease.deviceId,
    incarnation: lease.incarnation,
    expiresAt: lease.expiresAt,
  };
}

function leaseError(code, message) {
  const err = new Error(code + ": " + message);
  err.code = code;
  return err;
}

module.exports = { DeviceRegistry, LEASE_TTL_MS };
