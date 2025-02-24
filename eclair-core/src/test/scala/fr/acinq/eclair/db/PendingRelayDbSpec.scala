/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.db

import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs
import org.scalatest.funsuite.AnyFunSuite


class PendingRelayDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database 2 times in a row") {
    forAllDbs { dbs =>
      val db1 = dbs.pendingRelay
      val db2 = dbs.pendingRelay
    }
  }

  test("add/remove/list messages") {
    forAllDbs { dbs =>
      val db = dbs.pendingRelay

      val channelId1 = randomBytes32
      val channelId2 = randomBytes32
      val msg0 = CMD_FULFILL_HTLC(0, randomBytes32)
      val msg1 = CMD_FULFILL_HTLC(1, randomBytes32)
      val msg2 = CMD_FAIL_HTLC(2, Left(randomBytes32))
      val msg3 = CMD_FAIL_HTLC(3, Left(randomBytes32))
      val msg4 = CMD_FAIL_MALFORMED_HTLC(4, randomBytes32, FailureMessageCodecs.BADONION)

      assert(db.listPendingRelay(channelId1).toSet === Set.empty)
      db.addPendingRelay(channelId1, msg0)
      db.addPendingRelay(channelId1, msg0) // duplicate
      db.addPendingRelay(channelId1, msg1)
      db.addPendingRelay(channelId1, msg2)
      db.addPendingRelay(channelId1, msg3)
      db.addPendingRelay(channelId1, msg4)
      db.addPendingRelay(channelId2, msg0) // same messages but for different channel
      db.addPendingRelay(channelId2, msg1)
      assert(db.listPendingRelay(channelId1).toSet === Set(msg0, msg1, msg2, msg3, msg4))
      assert(db.listPendingRelay(channelId2).toSet === Set(msg0, msg1))
      assert(db.listPendingRelay === Set((channelId1, msg0.id), (channelId1, msg1.id), (channelId1, msg2.id), (channelId1, msg3.id), (channelId1, msg4.id), (channelId2, msg0.id), (channelId2, msg1.id)))
      db.removePendingRelay(channelId1, msg1.id)
      assert(db.listPendingRelay === Set((channelId1, msg0.id), (channelId1, msg2.id), (channelId1, msg3.id), (channelId1, msg4.id), (channelId2, msg0.id), (channelId2, msg1.id)))
    }
  }

}
