// S05.01: device registry and exclusive lease semantics.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { DeviceRegistry } = require("../src/devices");

test("attach assigns opaque id and fresh incarnation per attachment", () => {
  const reg = new DeviceRegistry();
  const a = reg.attach({ attachmentEvent: "evt-1", vid: 0x303a, pid: 0x1001, interfaces: [0, 2] });
  const b = reg.attach({ attachmentEvent: "evt-1" });
  assert.equal(b.deviceId, a.deviceId, "same attachment event returns same device");
  const c = reg.attach({ attachmentEvent: "evt-2" });
  assert.notEqual(c.deviceId, a.deviceId);
  assert.notEqual(c.incarnation, a.incarnation);
});

test("same model devices stay distinguishable", () => {
  const reg = new DeviceRegistry();
  const a = reg.attach({ attachmentEvent: "a", vid: 0x303a, pid: 0x1001 });
  const b = reg.attach({ attachmentEvent: "b", vid: 0x303a, pid: 0x1001 });
  assert.notEqual(a.deviceId, b.deviceId);
});

test("exclusive lease: second session rejected, owner renews, release frees", () => {
  const reg = new DeviceRegistry();
  const dev = reg.attach({ attachmentEvent: "x" });

  const lease = reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s1" });
  assert.ok(lease.leaseId);

  // another session cannot take it
  assert.throws(
    () => reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s2" }),
    /device_busy/);

  // owner renews
  const renewed = reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s1" });
  assert.equal(renewed.leaseId, lease.leaseId);
  assert.ok(renewed.expiresAt >= lease.expiresAt);

  // wrong session cannot release
  assert.throws(
    () => reg.releaseLease({ deviceId: dev.deviceId, leaseId: lease.leaseId, sessionSid: "s2" }),
    /not_lease_owner/);

  // owner releases; another session can then lease
  const released = reg.releaseLease({ deviceId: dev.deviceId, leaseId: lease.leaseId, sessionSid: "s1" });
  assert.equal(released.released, true);
  const next = reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s2" });
  assert.notEqual(next.leaseId, lease.leaseId);
});

test("stale incarnation is rejected after re-attachment", () => {
  const reg = new DeviceRegistry();
  const first = reg.attach({ attachmentEvent: "e1" });
  reg.detach(first.deviceId);
  const second = reg.attach({ attachmentEvent: "e2" });
  assert.equal(first.deviceId === second.deviceId, false);
  assert.throws(
    () => reg.requestLease({ deviceId: second.deviceId, incarnation: first.incarnation, sessionSid: "s1" }),
    /stale_incarnation/);
});

test("detach kills the lease with the attachment", () => {
  const reg = new DeviceRegistry();
  const dev = reg.attach({ attachmentEvent: "y" });
  reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s1" });
  reg.detach(dev.deviceId);
  assert.equal(reg.list().length, 0);
  assert.throws(
    () => reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s1" }),
    /device_unknown/);
});

test("expired lease frees the device without an explicit release", () => {
  const reg = new DeviceRegistry();
  const dev = reg.attach({ attachmentEvent: "z" });
  reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s1" });
  reg._expire(dev.deviceId);
  const next = reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s2" });
  assert.ok(next.leaseId);
});

test("lease ids are unique across leases", () => {
  const reg = new DeviceRegistry();
  const dev = reg.attach({ attachmentEvent: "u" });
  const ids = new Set();
  for (let i = 0; i < 5; i++) {
    const lease = reg.requestLease({ deviceId: dev.deviceId, incarnation: dev.incarnation, sessionSid: "s" + i });
    ids.add(lease.leaseId);
    reg.releaseLease({ deviceId: dev.deviceId, leaseId: lease.leaseId, sessionSid: "s" + i });
  }
  assert.equal(ids.size, 5);
});
