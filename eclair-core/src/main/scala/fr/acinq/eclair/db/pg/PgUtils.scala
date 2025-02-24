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

package fr.acinq.eclair.db.pg

import fr.acinq.eclair.db.jdbc.JdbcUtils
import fr.acinq.eclair.db.pg.PgUtils.PgLock.LockFailureHandler.LockException
import grizzled.slf4j.Logging
import org.postgresql.util.{PGInterval, PSQLException}

import java.sql.{Connection, Statement, Timestamp}
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration._

object PgUtils extends JdbcUtils {

  /** We raise this exception when the jdbc url changes, to prevent using a different server involuntarily. */
  case class JdbcUrlChanged(before: String, after: String) extends RuntimeException(s"The database URL has changed since the last start. It was `$before`, now it's `$after`")

  sealed trait PgLock {
    def obtainExclusiveLock(implicit ds: DataSource): Unit

    def withLock[T](f: Connection => T)(implicit ds: DataSource): T
  }

  object PgLock extends Logging {

    // @formatter:off
    sealed trait LockFailure
    object LockFailure {
      case object TooManyLockAttempts extends LockFailure
      case class AlreadyLocked(lockedBy: UUID) extends LockFailure
      case object LeaseExpired extends LockFailure
      case object NoLeaseInfo extends LockFailure
      case class GeneralLockException(cause: Throwable) extends LockFailure
    }
    // @formatter:on

    type LockFailureHandler = LockFailure => Unit

    object LockFailureHandler {
      def log: LockFailureHandler = {
        case LockFailure.GeneralLockException(cause) =>
          logger.error("cannot obtain lock on the database.\n", cause)
        case other =>
          logger.error(s"cannot obtain lock on the database ($other).")
      }

      case class LockException(lockFailure: LockFailure) extends RuntimeException("a lock exception occurred")

      /**
       * This handler is useful in tests
       */
      def logAndThrow: LockFailureHandler = { ex =>
        log(ex)
        throw LockException(ex)
      }

      /**
       * This is the recommended handler in production
       */
      def logAndStop: LockFailureHandler = { ex =>
        log(ex)
        logger.error("db locking error is a fatal error")
        sys.exit(-2)
      }
    }


    case object NoLock extends PgLock {
      override def obtainExclusiveLock(implicit ds: DataSource): Unit = ()

      override def withLock[T](f: Connection => T)(implicit ds: DataSource): T =
        inTransaction(f)
    }

    /**
     * This class represents a lease based locking mechanism [[https://en.wikipedia.org/wiki/Lease_(computer_science]].
     * It allows only one process to access the database at a time.
     *
     * `obtainExclusiveLock` method updates the record in `lease` table with the instance id and the expiration date
     * calculated as the current time plus the lease duration. If the current lease is not expired or it belongs to
     * another instance `obtainExclusiveLock` throws an exception.
     *
     * withLock method executes its `f` function and reads the record from lease table to checks if this instance still
     * holds the lease and it's not expired. If so, the database transaction gets committed, otherwise en exception is thrown.
     *
     * `lockExceptionHandler` provides a lock exception handler to customize the behavior when locking errors occur.
     */
    case class LeaseLock(instanceId: UUID, leaseDuration: FiniteDuration, leaseRenewInterval: FiniteDuration, lockFailureHandler: LockFailureHandler) extends PgLock {

      import LeaseLock._

      override def obtainExclusiveLock(implicit ds: DataSource): Unit = {
        obtainDatabaseLease(instanceId, leaseDuration) match {
          case Right(_) => ()
          case Left(ex) => lockFailureHandler(ex)
        }
      }

      override def withLock[T](f: Connection => T)(implicit ds: DataSource): T = {
        inTransaction { connection =>
          val res = f(connection)
          checkDatabaseLease(connection, instanceId) match {
            case Right(_) => ()
            case Left(ex) =>
              lockFailureHandler(ex)
              // at this point, a sane failure handler would have either thrown an exception or stopped the app
              // but we can't be careful enough so we make sure we throw here
              throw LockException(ex)
          }
          res
        }
      }
    }

    object LeaseLock {

      private val LeaseTable: String = "lease"

      /** We use a [[LeaseLock]] mechanism to get a [[LockLease]]. */
      case class LockLease(expiresAt: Timestamp, instanceId: UUID, expired: Boolean)

      private def obtainDatabaseLease(instanceId: UUID, leaseDuration: FiniteDuration, attempt: Int = 1)(implicit ds: DataSource): Either[LockFailure, LockLease] = synchronized {
        logger.debug(s"trying to acquire database lease (attempt #$attempt) instance ID=$instanceId")

        // this is a recursive method, we need to make sure we don't enter an infinite loop
        if (attempt > 3) return Left(LockFailure.TooManyLockAttempts)

        try {
          inTransaction { implicit connection =>
            acquireExclusiveTableLock()
            logger.debug("database lease was successfully acquired")
            checkDatabaseLease(connection, instanceId) match {
              case Right(_) =>
                Right(updateLease(instanceId, leaseDuration))
              case Left(LockFailure.LeaseExpired) =>
                // the previous lease has expired, we can take over
                // this happens if we have stopped the app, waited some the previous lease to expire, and then restarted
                Right(updateLease(instanceId, leaseDuration))
              case Left(LockFailure.NoLeaseInfo) =>
                // this is the first lock we ever put on the table
                Right(updateLease(instanceId, leaseDuration, insertNew = true))
              case otherFailure => otherFailure
            }
          }
        } catch {
          case e: PSQLException if e.getServerErrorMessage != null && e.getServerErrorMessage.getSQLState == "42P01" =>
            withConnection {
              connection =>
                logger.warn(s"table $LeaseTable does not exist, trying to create it")
                initializeLeaseTable(connection)
                obtainDatabaseLease(instanceId, leaseDuration, attempt + 1)
            }
          case t: Throwable => Left(LockFailure.GeneralLockException(t))
        }
      }

      private def initializeLeaseTable(implicit connection: Connection): Unit = {
        using(connection.createStatement()) {
          statement =>
            // allow only one row in the ownership lease table
            statement.executeUpdate(s"CREATE TABLE IF NOT EXISTS $LeaseTable (id INTEGER PRIMARY KEY default(1), expires_at TIMESTAMP NOT NULL, instance VARCHAR NOT NULL, CONSTRAINT one_row CHECK (id = 1))")
        }
      }

      private def acquireExclusiveTableLock()(implicit connection: Connection): Unit = {
        using(connection.createStatement()) {
          statement =>
            statement.executeUpdate(s"LOCK TABLE $LeaseTable IN ACCESS EXCLUSIVE MODE NOWAIT")
        }
      }

      private def checkDatabaseLease(connection: Connection, instanceId: UUID): Either[LockFailure, LockLease] = {
        try {
          getCurrentLease(connection) match {
            case Some(lease) =>
              if (lease.expired) {
                Left(LockFailure.LeaseExpired)
              } else if (lease.instanceId != instanceId) {
                Left(LockFailure.AlreadyLocked(lease.instanceId))
              } else {
                Right(lease)
              }
            case None =>
              Left(LockFailure.NoLeaseInfo)
          }
        } catch {
          case t: Throwable => Left(LockFailure.GeneralLockException(t))
        }
      }

      private def getCurrentLease(implicit connection: Connection): Option[LockLease] = {
        using(connection.createStatement()) {
          statement =>
            val rs = statement.executeQuery(s"SELECT expires_at, instance, now() > expires_at AS expired FROM $LeaseTable WHERE id = 1")
            if (rs.next())
              Some(LockLease(
                expiresAt = rs.getTimestamp("expires_at"),
                instanceId = UUID.fromString(rs.getString("instance")),
                expired = rs.getBoolean("expired")))
            else
              None
        }
      }

      private def updateLease(instanceId: UUID, leaseDuration: FiniteDuration, insertNew: Boolean = false)(implicit connection: Connection): LockLease = {
        val sql = if (insertNew)
          s"INSERT INTO $LeaseTable (expires_at, instance) VALUES (now() + ?, ?)"
        else
          s"UPDATE $LeaseTable SET expires_at = now() + ?, instance = ? WHERE id = 1"
        using(connection.prepareStatement(sql)) {
          statement =>
            statement.setObject(1, new PGInterval(s"${leaseDuration.toSeconds} seconds"))
            statement.setString(2, instanceId.toString)
            statement.executeUpdate()
        }
        getCurrentLease.get // TODO: improve that (do INSERT/UPDATE+SELECT?)
      }
    }

  }

  def inTransaction[T](connection: Connection)(f: Connection => T): T = {
    val autoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    val isolationLevel = connection.getTransactionIsolation
    connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
    try {
      val res = f(connection)
      connection.commit()
      res
    } catch {
      case ex: Throwable =>
        connection.rollback()
        throw ex
    } finally {
      connection.setAutoCommit(autoCommit)
      connection.setTransactionIsolation(isolationLevel)
    }
  }

  def inTransaction[T](f: Connection => T)(implicit dataSource: DataSource): T = {
    withConnection { connection =>
      inTransaction(connection)(f)
    }
  }

  /**
   * Several logical databases (channels, network, peers) may be stored in the same physical postgres database.
   * We keep track of their respective version using a dedicated table. The version entry will be created if
   * there is none but will never be updated here (use setVersion to do that).
   */
  def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // if there was no version for the current db, then insert the current version
    statement.executeUpdate(s"INSERT INTO versions VALUES ('$db_name', $currentVersion) ON CONFLICT DO NOTHING")
    // if there was a previous version installed, this will return a different value from current version
    val res = statement.executeQuery(s"SELECT version FROM versions WHERE db_name='$db_name'")
    res.next()
    res.getInt("version")
  }

  /**
   * Updates the version for a particular logical database, it will overwrite the previous version.
   */
  def setVersion(statement: Statement, db_name: String, newVersion: Int): Unit = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // overwrite the existing version
    statement.executeUpdate(s"UPDATE versions SET version=$newVersion WHERE db_name='$db_name'")
  }

}
