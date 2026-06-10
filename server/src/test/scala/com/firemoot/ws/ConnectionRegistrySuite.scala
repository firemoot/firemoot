package com.firemoot.ws

import munit.CatsEffectSuite

class ConnectionRegistrySuite extends CatsEffectSuite:

  test("first/last connection per user drives online/offline transitions") {
    ConnectionRegistry.create.flatMap { registry =>
      for
        aliceFirst <- registry.register("c1", "alice")
        aliceSecond <- registry.register("c2", "alice")
        bobFirst <- registry.register("c3", "bob")
        onlineBoth <- registry.onlineUsers
        total <- registry.count

        dropC1 <- registry.unregister("c1")
        aliceStillOnline <- registry.onlineUsers
        dropC2 <- registry.unregister("c2")
        onlyBob <- registry.onlineUsers
        dropUnknown <- registry.unregister("does-not-exist")
      yield
        assert(aliceFirst, "alice's first connection is a transition to online")
        assert(!aliceSecond, "alice's second connection is not a new transition")
        assert(bobFirst, "bob's first connection is a transition to online")
        assertEquals(onlineBoth, Set("alice", "bob"))
        assertEquals(total, 3)

        assertEquals(dropC1, Some(("alice", false)), "alice still has c2 open")
        assertEquals(aliceStillOnline, Set("alice", "bob"))
        assertEquals(dropC2, Some(("alice", true)), "alice's last connection closed")
        assertEquals(onlyBob, Set("bob"))
        assertEquals(dropUnknown, None, "unknown connections are ignored")
    }
  }
