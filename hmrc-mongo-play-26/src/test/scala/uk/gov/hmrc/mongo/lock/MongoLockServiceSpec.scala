/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.lock
import java.time.{LocalDateTime, ZoneOffset}

import com.mongodb.client.model.Filters.{eq => mongoEq}
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.{Completed, Document, MongoClient, MongoDatabase}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, WordSpecLike}
import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.lock.model.Lock
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class MongoLockServiceSpec extends WordSpecLike with Matchers with DefaultMongoCollectionSupport with ScalaFutures {

  "attemptLockWithRelease" should {
    "obtain lock, run the block supplied and release the lock" in {

      mongoLockservice.attemptLockWithRelease {
        val lock = find(lockId).futureValue.head

        lock.id             shouldBe lockId
        lock.expiryTime     shouldBe lock.timeCreated.plusSeconds(1)
        count().futureValue shouldBe 1

        Future.successful("result")
      }.futureValue shouldBe Some("result")

      count().futureValue shouldBe 0
    }

    "obtain lock, run the block supplied and release the lock when the block returns a failed future" in {
      a[RuntimeException] should be thrownBy {
        mongoLockservice.attemptLockWithRelease(Future.failed(new RuntimeException)).futureValue
      }
      count().futureValue shouldBe 0
    }

    "obtain lock, run the block supplied and release the lock when the block throws an exception" in {
      a[RuntimeException] should be thrownBy {
        mongoLockservice.attemptLockWithRelease(throw new RuntimeException).futureValue
      }
      count().futureValue shouldBe 0
    }

    "not run the block supplied if the lock is owned by someone else and return None" in {
      val existingLock = Lock(lockId, "owner2", now, now.plusSeconds(100))
      insert(existingLock).futureValue

      mongoLockservice
        .attemptLockWithRelease(fail("Should not execute!"))
        .futureValue shouldBe None

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

    "not run the block supplied if the lock is already owned by the caller and return None" in {
      val existingLock = Lock(lockId, owner, now, now.plusSeconds(100))
      insert(existingLock).futureValue

      mongoLockservice
        .attemptLockWithRelease(fail("Should not execute!"))
        .futureValue shouldBe None

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

  }

  "attemptLockWithRefreshExpiry" should {

    "execute the body if no previous lock is set" in {
      var counter = 0
      mongoLockservice.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue
      counter shouldBe 1
    }

    "execute the body if the lock for same serverId exists" in {
      var counter = 0
      mongoLockservice.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      mongoLockservice.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }

    "not execute the body and exit if the lock for another serverId exists" in {
      var counter = 0
      mongoLockRepository
        .toService(lockId, ttl)
        .attemptLockWithRefreshExpiry {
          Future.successful(counter += 1)
        }
        .futureValue

      mongoLockservice.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 1

    }

    "execute the body if run after the ttl time has expired" in {
      var counter = 0
      mongoLockservice.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      Thread.sleep(1000 + 1)

      mongoLockservice.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }
  }

  private val lockId        = "lockId"
  private val owner         = "owner"
  private val ttl: Duration = 1000.millis
  private val now           = LocalDateTime.now(ZoneOffset.UTC)

  private val mongoComponent = new MongoComponent {
    override def client: MongoClient     = mongoClient
    override def database: MongoDatabase = mongoDatabase()
  }

  private val mongoLockRepository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport)
  private val mongoLockservice    = mongoLockRepository.toService(lockId, ttl)

  private def findAll(): Future[Seq[Lock]] =
    mongoCollection()
      .find()
      .toFuture
      .map(_.map(toLock))

  private def count(): Future[Long] =
    mongoCollection()
      .count()
      .toFuture()

  private def find(id: String): Future[Seq[Lock]] =
    mongoCollection()
      .find(mongoEq(Lock.id, id))
      .toFuture()
      .map(_.map(toLock))

  private def insert[T](obj: T)(implicit tjs: Writes[T]): Future[Completed] =
    mongoCollection()
      .insertOne(Document(Json.toJson(obj).toString()))
      .toFuture()

  override protected val collectionName: String   = "locks"
  override protected val indexes: Seq[IndexModel] = Seq()

  private def toLock(document: Document): Lock =
    Json.parse(document.toJson()).as[Lock]

}
