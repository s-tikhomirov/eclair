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

package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging

import scala.collection.immutable.SortedMap

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb with Logging {

  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  val DB_NAME = "network"
  val CURRENT_VERSION = 2

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 =>
        // channel_update are cheap to retrieve, so let's just wipe them out and they'll get resynced
        statement.execute("PRAGMA foreign_keys = ON")
        logger.warn("migrating network db version 1->2")
        statement.executeUpdate("ALTER TABLE channels RENAME COLUMN data TO channel_announcement")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_update_1 BLOB NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_update_2 BLOB NULL")
        statement.executeUpdate("DROP TABLE channel_updates")
        statement.execute("PRAGMA foreign_keys = OFF")
        setVersion(statement, DB_NAME, CURRENT_VERSION)
        logger.warn("migration complete")
      case 2 => () // nothing to do
      case unknown => throw new IllegalArgumentException(s"unknown version $unknown for network db")
    }
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid TEXT NOT NULL, channel_announcement BLOB NOT NULL, capacity_sat INTEGER NOT NULL, channel_update_1 BLOB NULL, channel_update_2 BLOB NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
  }

  override def addNode(n: NodeAnnouncement): Unit = withMetrics("network/add-node") {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")) { statement =>
      statement.setBytes(1, n.nodeId.value.toArray)
      statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = withMetrics("network/update-node") {
    using(sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.setBytes(2, n.nodeId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def getNode(nodeId: Crypto.PublicKey): Option[NodeAnnouncement] = withMetrics("network/get-node") {
    using(sqlite.prepareStatement("SELECT data FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      val rs = statement.executeQuery()
      codecSequence(rs, nodeAnnouncementCodec).headOption
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = withMetrics("network/remove-node") {
    using(sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = withMetrics("network/list-nodes") {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM nodes")
      codecSequence(rs, nodeAnnouncementCodec)
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = withMetrics("network/add-channel") {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?, ?, NULL, NULL)")) { statement =>
      statement.setLong(1, c.shortChannelId.toLong)
      statement.setString(2, txid.toHex)
      statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
      statement.setLong(4, capacity.toLong)
      statement.executeUpdate()
    }
  }

  override def updateChannel(u: ChannelUpdate): Unit = withMetrics("network/update-channel") {
    val column = if (u.isNode1) "channel_update_1" else "channel_update_2"
    using(sqlite.prepareStatement(s"UPDATE channels SET $column=? WHERE short_channel_id=?")) { statement =>
      statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
      statement.setLong(2, u.shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def listChannels(): SortedMap[ShortChannelId, PublicChannel] = withMetrics("network/list-channels") {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT channel_announcement, txid, capacity_sat, channel_update_1, channel_update_2 FROM channels")
      var m = SortedMap.empty[ShortChannelId, PublicChannel]
      while (rs.next()) {
        val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
        val txId = ByteVector32.fromValidHex(rs.getString("txid"))
        val capacity = rs.getLong("capacity_sat")
        val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
        val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
        m = m + (ann.shortChannelId -> PublicChannel(ann, txId, Satoshi(capacity), channel_update_1_opt, channel_update_2_opt, None))
      }
      m
    }
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/remove-channels") {
    using(sqlite.createStatement) { statement =>
      shortChannelIds
        .grouped(1000) // remove channels by batch of 1000
        .foreach { _ =>
          val ids = shortChannelIds.map(_.toLong).mkString(",")
          statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id IN ($ids)")
        }
    }
  }

  override def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/add-to-pruned") {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pruned VALUES (?)"), inTransaction = true) { statement =>
      shortChannelIds.foreach(shortChannelId => {
        statement.setLong(1, shortChannelId.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = withMetrics("network/remove-from-pruned") {
    using(sqlite.createStatement) { statement =>
      statement.executeUpdate(s"DELETE FROM pruned WHERE short_channel_id=${shortChannelId.toLong}")
    }
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = withMetrics("network/is-pruned") {
    using(sqlite.prepareStatement("SELECT short_channel_id from pruned WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      val rs = statement.executeQuery()
      rs.next()
    }
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()
}
