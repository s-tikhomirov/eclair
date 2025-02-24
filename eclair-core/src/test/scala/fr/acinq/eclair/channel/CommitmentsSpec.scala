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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{DeterministicWallet, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.TestConstants.TestFeeEstimator
import fr.acinq.eclair.blockchain.fee.{FeeTargets, FeeratePerKw, FeerateTolerance, OnChainFeeConf}
import fr.acinq.eclair.channel.Commitments._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.states.StateTestsBase
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.wire.protocol.{IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{TestKitBaseClass, _}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.Random

class CommitmentsSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  val feeConfNoMismatch = OnChainFeeConf(FeeTargets(6, 2, 2, 6), new TestFeeEstimator, closeOnOfflineMismatch = false, 1.0, FeerateTolerance(0.00001, 100000.0, TestConstants.anchorOutputsFeeratePerKw), Map.empty)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("correct values for availableForSend/availableForReceive (success case)") { f =>
    import f._

    val a = 758640000 msat // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p = 42000000 msat // a->b payment
    val htlcOutputFee = 2 * 1720000 msat // fee due to the additional htlc output; we count it twice because we keep a reserve for a x2 feerate increase

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (payment_preimage, cmdAdd) = makeCmdAdd(p, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac1.availableBalanceForSend == a - p - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val Right(bc1) = receiveAdd(bc0, add, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc1.availableBalanceForSend == b)
    assert(bc1.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac2, commit1)) = sendCommit(ac1, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac2.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac2.availableBalanceForReceive == b)

    val Right((bc2, revocation1)) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc2.availableBalanceForSend == b)
    assert(bc2.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac3, _)) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac3.availableBalanceForReceive == b)

    val Right((bc3, commit2)) = sendCommit(bc2, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc3.availableBalanceForSend == b)
    assert(bc3.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac4, revocation2)) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac4.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac4.availableBalanceForReceive == b)

    val Right((bc4, _)) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == b)
    assert(bc4.availableBalanceForReceive == a - p - htlcOutputFee)

    val cmdFulfill = CMD_FULFILL_HTLC(0, payment_preimage)
    val Right((bc5, fulfill)) = sendFulfill(bc4, cmdFulfill)
    assert(bc5.availableBalanceForSend == b + p) // as soon as we have the fulfill, the balance increases
    assert(bc5.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac5, _, _)) = receiveFulfill(ac4, fulfill)
    assert(ac5.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac5.availableBalanceForReceive == b + p)

    val Right((bc6, commit3)) = sendCommit(bc5, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc6.availableBalanceForSend == b + p)
    assert(bc6.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac6, revocation3)) = receiveCommit(ac5, commit3, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac6.availableBalanceForSend == a - p)
    assert(ac6.availableBalanceForReceive == b + p)

    val Right((bc7, _)) = receiveRevocation(bc6, revocation3)
    assert(bc7.availableBalanceForSend == b + p)
    assert(bc7.availableBalanceForReceive == a - p)

    val Right((ac7, commit4)) = sendCommit(ac6, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac7.availableBalanceForSend == a - p)
    assert(ac7.availableBalanceForReceive == b + p)

    val Right((bc8, revocation4)) = receiveCommit(bc7, commit4, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc8.availableBalanceForSend == b + p)
    assert(bc8.availableBalanceForReceive == a - p)

    val Right((ac8, _)) = receiveRevocation(ac7, revocation4)
    assert(ac8.availableBalanceForSend == a - p)
    assert(ac8.availableBalanceForReceive == b + p)
  }

  test("correct values for availableForSend/availableForReceive (failure case)") { f =>
    import f._

    val a = 758640000 msat // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p = 42000000 msat // a->b payment
    val htlcOutputFee = 2 * 1720000 msat // fee due to the additional htlc output; we count it twice because we keep a reserve for a x2 feerate increase

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (_, cmdAdd) = makeCmdAdd(p, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add)) = sendAdd(ac0, cmdAdd, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac1.availableBalanceForSend == a - p - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val Right(bc1) = receiveAdd(bc0, add, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc1.availableBalanceForSend == b)
    assert(bc1.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac2, commit1)) = sendCommit(ac1, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac2.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac2.availableBalanceForReceive == b)

    val Right((bc2, revocation1)) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc2.availableBalanceForSend == b)
    assert(bc2.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac3, _)) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac3.availableBalanceForReceive == b)

    val Right((bc3, commit2)) = sendCommit(bc2, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc3.availableBalanceForSend == b)
    assert(bc3.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac4, revocation2)) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac4.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac4.availableBalanceForReceive == b)

    val Right((bc4, _)) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == b)
    assert(bc4.availableBalanceForReceive == a - p - htlcOutputFee)

    val cmdFail = CMD_FAIL_HTLC(0, Right(IncorrectOrUnknownPaymentDetails(p, 42)))
    val Right((bc5, fail)) = sendFail(bc4, cmdFail, bob.underlyingActor.nodeParams.privateKey)
    assert(bc5.availableBalanceForSend == b)
    assert(bc5.availableBalanceForReceive == a - p - htlcOutputFee) // a's balance won't return to previous before she acknowledges the fail

    val Right((ac5, _, _)) = receiveFail(ac4, fail)
    assert(ac5.availableBalanceForSend == a - p - htlcOutputFee)
    assert(ac5.availableBalanceForReceive == b)

    val Right((bc6, commit3)) = sendCommit(bc5, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc6.availableBalanceForSend == b)
    assert(bc6.availableBalanceForReceive == a - p - htlcOutputFee)

    val Right((ac6, revocation3)) = receiveCommit(ac5, commit3, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac6.availableBalanceForSend == a)
    assert(ac6.availableBalanceForReceive == b)

    val Right((bc7, _)) = receiveRevocation(bc6, revocation3)
    assert(bc7.availableBalanceForSend == b)
    assert(bc7.availableBalanceForReceive == a)

    val Right((ac7, commit4)) = sendCommit(ac6, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac7.availableBalanceForSend == a)
    assert(ac7.availableBalanceForReceive == b)

    val Right((bc8, revocation4)) = receiveCommit(bc7, commit4, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc8.availableBalanceForSend == b)
    assert(bc8.availableBalanceForReceive == a)

    val Right((ac8, _)) = receiveRevocation(ac7, revocation4)
    assert(ac8.availableBalanceForSend == a)
    assert(ac8.availableBalanceForReceive == b)
  }

  test("correct values for availableForSend/availableForReceive (multiple htlcs)") { f =>
    import f._

    val a = 758640000 msat // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p1 = 18000000 msat // a->b payment
    val p2 = 20000000 msat // a->b payment
    val p3 = 40000000 msat // b->a payment
    val htlcOutputFee = 2 * 1720000 msat // fee due to the additional htlc output; we count it twice because we keep a reserve for a x2 feerate increase

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > (p1 + p2)) // alice can afford the payments
    assert(bc0.availableBalanceForSend > p3) // bob can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val (payment_preimage1, cmdAdd1) = makeCmdAdd(p1, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add1)) = sendAdd(ac0, cmdAdd1, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac1.availableBalanceForSend == a - p1 - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val (_, cmdAdd2) = makeCmdAdd(p2, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac2, add2)) = sendAdd(ac1, cmdAdd2, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac2.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac2.availableBalanceForReceive == b)

    val (payment_preimage3, cmdAdd3) = makeCmdAdd(p3, alice.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((bc1, add3)) = sendAdd(bc0, cmdAdd3, currentBlockHeight, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc1.availableBalanceForSend == b - p3) // bob doesn't pay the fee
    assert(bc1.availableBalanceForReceive == a)

    val Right(bc2) = receiveAdd(bc1, add1, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc2.availableBalanceForSend == b - p3)
    assert(bc2.availableBalanceForReceive == a - p1 - htlcOutputFee)

    val Right(bc3) = receiveAdd(bc2, add2, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc3.availableBalanceForSend == b - p3)
    assert(bc3.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee)

    val Right(ac3) = receiveAdd(ac2, add3, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac3.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee)
    assert(ac3.availableBalanceForReceive == b - p3)

    val Right((ac4, commit1)) = sendCommit(ac3, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac4.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee)
    assert(ac4.availableBalanceForReceive == b - p3)

    val Right((bc4, revocation1)) = receiveCommit(bc3, commit1, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc4.availableBalanceForSend == b - p3)
    assert(bc4.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee)

    val Right((ac5, _)) = receiveRevocation(ac4, revocation1)
    assert(ac5.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee)
    assert(ac5.availableBalanceForReceive == b - p3)

    val Right((bc5, commit2)) = sendCommit(bc4, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc5.availableBalanceForSend == b - p3)
    assert(bc5.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee)

    val Right((ac6, revocation2)) = receiveCommit(ac5, commit2, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac6.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee) // alice has acknowledged b's hltc so it needs to pay the fee for it
    assert(ac6.availableBalanceForReceive == b - p3)

    val Right((bc6, _)) = receiveRevocation(bc5, revocation2)
    assert(bc6.availableBalanceForSend == b - p3)
    assert(bc6.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)

    val Right((ac7, commit3)) = sendCommit(ac6, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac7.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)
    assert(ac7.availableBalanceForReceive == b - p3)

    val Right((bc7, revocation3)) = receiveCommit(bc6, commit3, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc7.availableBalanceForSend == b - p3)
    assert(bc7.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)

    val Right((ac8, _)) = receiveRevocation(ac7, revocation3)
    assert(ac8.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)
    assert(ac8.availableBalanceForReceive == b - p3)

    val cmdFulfill1 = CMD_FULFILL_HTLC(0, payment_preimage1)
    val Right((bc8, fulfill1)) = sendFulfill(bc7, cmdFulfill1)
    assert(bc8.availableBalanceForSend == b + p1 - p3) // as soon as we have the fulfill, the balance increases
    assert(bc8.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee)

    val cmdFail2 = CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(p2, 42)))
    val Right((bc9, fail2)) = sendFail(bc8, cmdFail2, bob.underlyingActor.nodeParams.privateKey)
    assert(bc9.availableBalanceForSend == b + p1 - p3)
    assert(bc9.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee - htlcOutputFee) // a's balance won't return to previous before she acknowledges the fail

    val cmdFulfill3 = CMD_FULFILL_HTLC(0, payment_preimage3)
    val Right((ac9, fulfill3)) = sendFulfill(ac8, cmdFulfill3)
    assert(ac9.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3) // as soon as we have the fulfill, the balance increases
    assert(ac9.availableBalanceForReceive == b - p3)

    val Right((ac10, _, _)) = receiveFulfill(ac9, fulfill1)
    assert(ac10.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
    assert(ac10.availableBalanceForReceive == b + p1 - p3)

    val Right((ac11, _, _)) = receiveFail(ac10, fail2)
    assert(ac11.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
    assert(ac11.availableBalanceForReceive == b + p1 - p3)

    val Right((bc10, _, _)) = receiveFulfill(bc9, fulfill3)
    assert(bc10.availableBalanceForSend == b + p1 - p3)
    assert(bc10.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3) // the fee for p3 disappears

    val Right((ac12, commit4)) = sendCommit(ac11, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac12.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
    assert(ac12.availableBalanceForReceive == b + p1 - p3)

    val Right((bc11, revocation4)) = receiveCommit(bc10, commit4, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc11.availableBalanceForSend == b + p1 - p3)
    assert(bc11.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)

    val Right((ac13, _)) = receiveRevocation(ac12, revocation4)
    assert(ac13.availableBalanceForSend == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)
    assert(ac13.availableBalanceForReceive == b + p1 - p3)

    val Right((bc12, commit5)) = sendCommit(bc11, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc12.availableBalanceForSend == b + p1 - p3)
    assert(bc12.availableBalanceForReceive == a - p1 - htlcOutputFee - p2 - htlcOutputFee + p3)

    val Right((ac14, revocation5)) = receiveCommit(ac13, commit5, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac14.availableBalanceForSend == a - p1 + p3)
    assert(ac14.availableBalanceForReceive == b + p1 - p3)

    val Right((bc13, _)) = receiveRevocation(bc12, revocation5)
    assert(bc13.availableBalanceForSend == b + p1 - p3)
    assert(bc13.availableBalanceForReceive == a - p1 + p3)

    val Right((ac15, commit6)) = sendCommit(ac14, alice.underlyingActor.nodeParams.channelKeyManager)
    assert(ac15.availableBalanceForSend == a - p1 + p3)
    assert(ac15.availableBalanceForReceive == b + p1 - p3)

    val Right((bc14, revocation6)) = receiveCommit(bc13, commit6, bob.underlyingActor.nodeParams.channelKeyManager)
    assert(bc14.availableBalanceForSend == b + p1 - p3)
    assert(bc14.availableBalanceForReceive == a - p1 + p3)

    val Right((ac16, _)) = receiveRevocation(ac15, revocation6)
    assert(ac16.availableBalanceForSend == a - p1 + p3)
    assert(ac16.availableBalanceForReceive == b + p1 - p3)
  }

  // See https://github.com/lightningnetwork/lightning-rfc/issues/728
  test("funder keeps additional reserve to avoid channel being stuck") { f =>
    val isFunder = true
    val c = CommitmentsSpec.makeCommitments(100000000 msat, 50000000 msat, FeeratePerKw(2500 sat), 546 sat, isFunder)
    val (_, cmdAdd) = makeCmdAdd(c.availableBalanceForSend, randomKey.publicKey, f.currentBlockHeight)
    val Right((c1, _)) = sendAdd(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch)
    assert(c1.availableBalanceForSend === 0.msat)

    // We should be able to handle a fee increase.
    val Right((c2, _)) = sendFee(c1, CMD_UPDATE_FEE(FeeratePerKw(3000 sat)))

    // Now we shouldn't be able to send until we receive enough to handle the updated commit tx fee (even trimmed HTLCs shouldn't be sent).
    val (_, cmdAdd1) = makeCmdAdd(100 msat, randomKey.publicKey, f.currentBlockHeight)
    val Left(_: InsufficientFunds) = sendAdd(c2, cmdAdd1, f.currentBlockHeight, feeConfNoMismatch)
  }

  test("can send availableForSend") { f =>
    for (isFunder <- Seq(true, false)) {
      val c = CommitmentsSpec.makeCommitments(702000000 msat, 52000000 msat, FeeratePerKw(2679 sat), 546 sat, isFunder)
      val (_, cmdAdd) = makeCmdAdd(c.availableBalanceForSend, randomKey.publicKey, f.currentBlockHeight)
      val result = sendAdd(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch)
      assert(result.isRight, result)
    }
  }

  test("can receive availableForReceive") { f =>
    for (isFunder <- Seq(true, false)) {
      val c = CommitmentsSpec.makeCommitments(31000000 msat, 702000000 msat, FeeratePerKw(2679 sat), 546 sat, isFunder)
      val add = UpdateAddHtlc(randomBytes32, c.remoteNextHtlcId, c.availableBalanceForReceive, randomBytes32, CltvExpiry(f.currentBlockHeight), TestConstants.emptyOnionPacket)
      receiveAdd(c, add, feeConfNoMismatch)
    }
  }

  test("should always be able to send availableForSend", Tag("fuzzy")) { f =>
    val maxPendingHtlcAmount = 1000000.msat
    case class FuzzTest(isFunder: Boolean, pendingHtlcs: Int, feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, toLocal: MilliSatoshi, toRemote: MilliSatoshi)
    for (_ <- 1 to 100) {
      val t = FuzzTest(
        isFunder = Random.nextInt(2) == 0,
        pendingHtlcs = Random.nextInt(10),
        feeRatePerKw = FeeratePerKw(Random.nextInt(10000).max(1).sat),
        dustLimit = Random.nextInt(1000).sat,
        // We make sure both sides have enough to send/receive at least the initial pending HTLCs.
        toLocal = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat,
        toRemote = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat)
      var c = CommitmentsSpec.makeCommitments(t.toLocal, t.toRemote, t.feeRatePerKw, t.dustLimit, t.isFunder)
      // Add some initial HTLCs to the pending list (bigger commit tx).
      for (_ <- 0 to t.pendingHtlcs) {
        val amount = Random.nextInt(maxPendingHtlcAmount.toLong.toInt).msat.max(1 msat)
        val (_, cmdAdd) = makeCmdAdd(amount, randomKey.publicKey, f.currentBlockHeight)
        sendAdd(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch) match {
          case Right((cc, _)) => c = cc
          case Left(e) => fail(s"$t -> could not setup initial htlcs: $e")
        }
      }
      val (_, cmdAdd) = makeCmdAdd(c.availableBalanceForSend, randomKey.publicKey, f.currentBlockHeight)
      val result = sendAdd(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch)
      assert(result.isRight, s"$t -> $result")
    }
  }

  test("should always be able to receive availableForReceive", Tag("fuzzy")) { f =>
    val maxPendingHtlcAmount = 1000000.msat
    case class FuzzTest(isFunder: Boolean, pendingHtlcs: Int, feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, toLocal: MilliSatoshi, toRemote: MilliSatoshi)
    for (_ <- 1 to 100) {
      val t = FuzzTest(
        isFunder = Random.nextInt(2) == 0,
        pendingHtlcs = Random.nextInt(10),
        feeRatePerKw = FeeratePerKw(Random.nextInt(10000).max(1).sat),
        dustLimit = Random.nextInt(1000).sat,
        // We make sure both sides have enough to send/receive at least the initial pending HTLCs.
        toLocal = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat,
        toRemote = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat)
      var c = CommitmentsSpec.makeCommitments(t.toLocal, t.toRemote, t.feeRatePerKw, t.dustLimit, t.isFunder)
      // Add some initial HTLCs to the pending list (bigger commit tx).
      for (_ <- 0 to t.pendingHtlcs) {
        val amount = Random.nextInt(maxPendingHtlcAmount.toLong.toInt).msat.max(1 msat)
        val add = UpdateAddHtlc(randomBytes32, c.remoteNextHtlcId, amount, randomBytes32, CltvExpiry(f.currentBlockHeight), TestConstants.emptyOnionPacket)
        receiveAdd(c, add, feeConfNoMismatch) match {
          case Right(cc) => c = cc
          case Left(e) => fail(s"$t -> could not setup initial htlcs: $e")
        }
      }
      val add = UpdateAddHtlc(randomBytes32, c.remoteNextHtlcId, c.availableBalanceForReceive, randomBytes32, CltvExpiry(f.currentBlockHeight), TestConstants.emptyOnionPacket)
      receiveAdd(c, add, feeConfNoMismatch) match {
        case Right(_) => ()
        case Left(e) => fail(s"$t -> $e")
      }
    }
  }

}

object CommitmentsSpec {

  def makeCommitments(toLocal: MilliSatoshi, toRemote: MilliSatoshi, feeRatePerKw: FeeratePerKw = FeeratePerKw(0 sat), dustLimit: Satoshi = 0 sat, isFunder: Boolean = true, announceChannel: Boolean = true): Commitments = {
    val localParams = LocalParams(randomKey.publicKey, DeterministicWallet.KeyPath(Seq(42L)), dustLimit, UInt64.MaxValue, 0 sat, 1 msat, CltvExpiryDelta(144), 50, isFunder, ByteVector.empty, None, Features.empty)
    val remoteParams = RemoteParams(randomKey.publicKey, dustLimit, UInt64.MaxValue, 0 sat, 1 msat, CltvExpiryDelta(144), 50, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, Features.empty)
    val commitmentInput = Funding.makeFundingInputInfo(randomBytes32, 0, (toLocal + toRemote).truncateToSatoshi, randomKey.publicKey, remoteParams.fundingPubKey)
    Commitments(
      ChannelVersion.STANDARD,
      localParams,
      remoteParams,
      channelFlags = if (announceChannel) ChannelFlags.AnnounceChannel else ChannelFlags.Empty,
      LocalCommit(0, CommitmentSpec(Set.empty, feeRatePerKw, toLocal, toRemote), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil)),
      RemoteCommit(0, CommitmentSpec(Set.empty, feeRatePerKw, toRemote, toLocal), randomBytes32, randomKey.publicKey),
      LocalChanges(Nil, Nil, Nil),
      RemoteChanges(Nil, Nil, Nil),
      localNextHtlcId = 1,
      remoteNextHtlcId = 1,
      originChannels = Map.empty,
      remoteNextCommitInfo = Right(randomKey.publicKey),
      commitInput = commitmentInput,
      remotePerCommitmentSecrets = ShaChain.init,
      channelId = randomBytes32)
  }

  def makeCommitments(toLocal: MilliSatoshi, toRemote: MilliSatoshi, localNodeId: PublicKey, remoteNodeId: PublicKey, announceChannel: Boolean): Commitments = {
    val localParams = LocalParams(localNodeId, DeterministicWallet.KeyPath(Seq(42L)), 0 sat, UInt64.MaxValue, 0 sat, 1 msat, CltvExpiryDelta(144), 50, isFunder = true, ByteVector.empty, None, Features.empty)
    val remoteParams = RemoteParams(remoteNodeId, 0 sat, UInt64.MaxValue, 0 sat, 1 msat, CltvExpiryDelta(144), 50, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, Features.empty)
    val commitmentInput = Funding.makeFundingInputInfo(randomBytes32, 0, (toLocal + toRemote).truncateToSatoshi, randomKey.publicKey, remoteParams.fundingPubKey)
    Commitments(
      ChannelVersion.STANDARD,
      localParams,
      remoteParams,
      channelFlags = if (announceChannel) ChannelFlags.AnnounceChannel else ChannelFlags.Empty,
      LocalCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(0 sat), toLocal, toRemote), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil)),
      RemoteCommit(0, CommitmentSpec(Set.empty, FeeratePerKw(0 sat), toRemote, toLocal), randomBytes32, randomKey.publicKey),
      LocalChanges(Nil, Nil, Nil),
      RemoteChanges(Nil, Nil, Nil),
      localNextHtlcId = 1,
      remoteNextHtlcId = 1,
      originChannels = Map.empty,
      remoteNextCommitInfo = Right(randomKey.publicKey),
      commitInput = commitmentInput,
      remotePerCommitmentSecrets = ShaChain.init,
      channelId = randomBytes32)
  }

}