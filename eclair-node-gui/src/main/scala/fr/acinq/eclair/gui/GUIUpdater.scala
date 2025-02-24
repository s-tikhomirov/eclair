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

package fr.acinq.eclair.gui

import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.CoinUtils
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor.{ZMQConnected, ZMQDisconnected}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.controllers._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.{NORMAL => _}
import fr.acinq.eclair.router._
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.layout.VBox

/**
 * Created by PM on 16/08/2016.
 */

class GUIUpdater(mainController: MainController) extends Actor with ActorLogging {

  val STATE_MUTUAL_CLOSE: Set[fr.acinq.eclair.channel.State] = Set(WAIT_FOR_INIT_INTERNAL, WAIT_FOR_OPEN_CHANNEL, WAIT_FOR_ACCEPT_CHANNEL, WAIT_FOR_FUNDING_INTERNAL, WAIT_FOR_FUNDING_CREATED, WAIT_FOR_FUNDING_SIGNED, NORMAL)
  val STATE_FORCE_CLOSE: Set[fr.acinq.eclair.channel.State] = Set(WAIT_FOR_FUNDING_CONFIRMED, WAIT_FOR_FUNDING_LOCKED, NORMAL, SHUTDOWN, NEGOTIATING, OFFLINE, SYNCING)

  /**
   * Needed to stop JavaFX complaining about updates from non GUI thread
   */
  private def runInGuiThread(f: () => Unit): Unit = {
    Platform.runLater(new Runnable() {
      @Override def run(): Unit = f()
    })
  }

  def receive: Receive = main(Map())

  def createChannelPanel(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, channelId: ByteVector32): (ChannelPaneController, VBox) = {
    log.info(s"new channel: $channel")
    val loader = new FXMLLoader(getClass.getResource("/gui/main/channelPane.fxml"))
    val channelPaneController = new ChannelPaneController(channel, remoteNodeId.toString())
    loader.setController(channelPaneController)
    val root = loader.load[VBox]
    channelPaneController.channelId.setText(channelId.toHex)
    channelPaneController.funder.setText(if (isFunder) "Yes" else "No")

    // set the node alias if the node has already been announced
    if (mainController.networkNodesMap.containsKey(remoteNodeId)) {
      val na = mainController.networkNodesMap.get(remoteNodeId)
      channelPaneController.updateRemoteNodeAlias(na.alias)
    }

    (channelPaneController, root)
  }

  def main(m: Map[ActorRef, ChannelPaneController]): Receive = {

    case ChannelCreated(channel, peer, remoteNodeId, isFunder, temporaryChannelId, _, _) =>
      context.watch(channel)
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, temporaryChannelId)
      runInGuiThread(() => mainController.channelBox.getChildren.addAll(root))
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelRestored(channel, channelId, peer, remoteNodeId, isFunder, currentData: Commitments) => // We are specifically interested in normal Commitments with funding txid here
      context.watch(channel)
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, channelId)
      channelPaneController.updateBalance(currentData)
      val m1 = m + (channel -> channelPaneController)
      val totalBalance = m1.values.map(_.getBalance).sum
      runInGuiThread(() => {
        channelPaneController.refreshBalance()
        mainController.refreshTotalBalance(totalBalance)
        channelPaneController.txId.setText(currentData.commitInput.outPoint.txid.toHex)
        mainController.channelBox.getChildren.addAll(root)
      })
      context.become(main(m1))

    case ShortChannelIdAssigned(channel, _, shortChannelId, _) if m.contains(channel) =>
      val channelPaneController = m(channel)
      runInGuiThread(() => channelPaneController.shortChannelId.setText(shortChannelId.toString))

    case ChannelIdAssigned(channel, _, _, channelId) if m.contains(channel) =>
      val channelPaneController = m(channel)
      runInGuiThread(() => channelPaneController.channelId.setText(channelId.toHex))

    case ChannelStateChanged(channel, _, _, _, _, currentState, commitments_opt) if m.contains(channel) =>
      val channelPaneController = m(channel)
      runInGuiThread { () =>
        (currentState, commitments_opt) match {
          case (WAIT_FOR_FUNDING_CONFIRMED, Some(c: Commitments)) => channelPaneController.txId.setText(c.commitInput.outPoint.txid.toHex)
          case _ =>
        }
        channelPaneController.close.setVisible(STATE_MUTUAL_CLOSE.contains(currentState))
        channelPaneController.forceclose.setVisible(STATE_FORCE_CLOSE.contains(currentState))
        channelPaneController.state.setText(currentState.toString)
      }

    case ChannelSignatureReceived(channel, commitments) if m.contains(channel) =>
      val channelPaneController = m(channel)
      channelPaneController.updateBalance(commitments)
      val totalBalance = m.values.map(_.getBalance).sum
      runInGuiThread(() => {
        channelPaneController.refreshBalance()
        mainController.refreshTotalBalance(totalBalance)
      })

    case Terminated(actor) if m.contains(actor) =>
      val channelPaneController = m(actor)
      log.debug(s"channel=${channelPaneController.channelId.getText} to be removed from gui")
      runInGuiThread(() => mainController.channelBox.getChildren.remove(channelPaneController.root))
      val m1 = m - actor
      val totalBalance = m1.values.map(_.getBalance).sum
      runInGuiThread(() => {
        mainController.refreshTotalBalance(totalBalance)
      })
      context.become(main(m1))

    case NodesDiscovered(nodeAnnouncements) =>
      runInGuiThread { () =>
        nodeAnnouncements.foreach { nodeAnnouncement =>
          log.debug(s"peer node discovered with node id={}", nodeAnnouncement.nodeId)
          if (!mainController.networkNodesMap.containsKey(nodeAnnouncement.nodeId)) {
            mainController.networkNodesMap.put(nodeAnnouncement.nodeId, nodeAnnouncement)
            m.foreach(f => if (nodeAnnouncement.nodeId.toString.equals(f._2.peerNodeId)) {
              f._2.updateRemoteNodeAlias(nodeAnnouncement.alias)
            })
          }
        }
      }

    case NodeLost(nodeId) =>
      log.debug(s"peer node lost with node id=$nodeId")
      runInGuiThread { () =>
        mainController.networkNodesMap.remove(nodeId)
      }

    case NodeUpdated(nodeAnnouncement) =>
      log.debug(s"peer node with id=${nodeAnnouncement.nodeId} has been updated")
      runInGuiThread { () =>
        mainController.networkNodesMap.put(nodeAnnouncement.nodeId, nodeAnnouncement)
        m.foreach(f => if (nodeAnnouncement.nodeId.toString.equals(f._2.peerNodeId)) {
          f._2.updateRemoteNodeAlias(nodeAnnouncement.alias)
        })
      }

    case ChannelsDiscovered(channelsDiscovered) =>
      runInGuiThread { () =>
        channelsDiscovered.foreach { case SingleChannelDiscovered(channelAnnouncement, capacity, _, _) =>
          log.debug(s"peer channel discovered with channel id={}", channelAnnouncement.shortChannelId)
          if (!mainController.networkChannelsMap.containsKey(channelAnnouncement.shortChannelId)) {
            mainController.networkChannelsMap.put(channelAnnouncement.shortChannelId, ChannelInfo(channelAnnouncement, None, None, None, None, capacity, None, None))
          }
        }
      }

    case ChannelLost(shortChannelId) =>
      log.debug(s"peer channel lost with channel id=$shortChannelId")
      runInGuiThread { () =>
        mainController.networkChannelsMap.remove(shortChannelId)
      }

    case ChannelUpdatesReceived(channelUpdates) =>
      runInGuiThread { () =>
        channelUpdates.foreach { channelUpdate =>
          log.debug(s"peer channel with id={} has been updated - flags: {} fees: {} {}", channelUpdate.shortChannelId, channelUpdate.channelFlags, channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths)
          if (mainController.networkChannelsMap.containsKey(channelUpdate.shortChannelId)) {
            val c = mainController.networkChannelsMap.get(channelUpdate.shortChannelId)
            if (Announcements.isNode1(channelUpdate.channelFlags)) {
              c.isNode1Enabled = Some(Announcements.isEnabled(channelUpdate.channelFlags))
              c.feeBaseMsatNode1_opt = Some(channelUpdate.feeBaseMsat.toLong)
              c.feeProportionalMillionthsNode1_opt = Some(channelUpdate.feeProportionalMillionths)
            } else {
              c.isNode2Enabled = Some(Announcements.isEnabled(channelUpdate.channelFlags))
              c.feeBaseMsatNode2_opt = Some(channelUpdate.feeBaseMsat.toLong)
              c.feeProportionalMillionthsNode2_opt = Some(channelUpdate.feeProportionalMillionths)
            }
            mainController.networkChannelsMap.put(channelUpdate.shortChannelId, c)
          }
        }
      }

    case p: PaymentFailed =>
      val distilledFailures = PaymentFailure.transformForUser(p.failures)
      val message = s"${distilledFailures.size} attempts:\n${
        distilledFailures.map {
          case LocalFailure(_, t) => s"- (local) ${t.getMessage}"
          case RemoteFailure(_, e) => s"- (remote) ${e.failureMessage.message}"
          case _ => "- Unknown error"
        }.mkString("\n")
      }"
      mainController.handlers.notification("Payment Failed", message, NOTIFICATION_ERROR)

    case p: PaymentSent =>
      log.debug(s"payment sent with h=${p.paymentHash}, amount=${p.recipientAmount}, fees=${p.feesPaid}")
      val message = CoinUtils.formatAmountInUnit(p.amountWithFees, FxApp.getUnit, withUnit = true)
      mainController.handlers.notification("Payment Sent", message, NOTIFICATION_SUCCESS)
      runInGuiThread(() => mainController.paymentSentList.add(PaymentSentRecord(p, LocalDateTime.now())))

    case p: PaymentReceived =>
      log.debug(s"payment received with h=${p.paymentHash}, amount=${p.amount}")
      runInGuiThread(() => mainController.paymentReceivedList.add(PaymentReceivedRecord(p, LocalDateTime.now())))

    case p: PaymentRelayed =>
      log.debug(s"payment relayed with h=${p.paymentHash}, amount=${p.amountIn}, feesEarned=${p.amountOut}")
      runInGuiThread(() => mainController.paymentRelayedList.add(PaymentRelayedRecord(p, LocalDateTime.now())))

    case ZMQConnected =>
      log.debug("ZMQ connection UP")
      runInGuiThread(() => mainController.hideBlockerModal)

    case ZMQDisconnected =>
      log.debug("ZMQ connection DOWN")
      runInGuiThread(() => mainController.showBlockerModal("Bitcoin Core"))

  }
}
