/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.workitem

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

import com.typesafe.config.ConfigFactory
import org.bson.types.ObjectId
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.TestSuite
import play.api.libs.json.Json
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

trait TimeSource {
  val timeSource = new {
    var now: Instant = Instant.now()

    def advanceADay() = setNowTo(now.plus(1, ChronoUnit.DAYS))

    def advance(duration: Duration) = setNowTo(now.plus(duration))

    def retreat1Day() = setNowTo(now.minus(1, ChronoUnit.DAYS))

    def retreatAlmostDay() = setNowTo(now.minus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES))

    def setNowTo(newNow: Instant) = {
      now = newNow
      now
    }
  }
}

trait WithWorkItemRepositoryModule
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItemWithModule]]
  with TimeSource {
    this: TestSuite =>

  val appConf = ConfigFactory.load("application.test.conf")

  implicit val eif = uk.gov.hmrc.workitem.ExampleItemWithModule.formats

  override lazy val repository = new WorkItemModuleRepository[ExampleItemWithModule](
    collectionName = "items",
    moduleName     = "testModule",
    mongoComponent = mongoComponent,
    config         = appConf
  ) {
    override val inProgressRetryAfterProperty: String = "retryAfterSeconds"

    override def now(): Instant = timeSource.now
  }
}

trait WithWorkItemRepository
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItem]]
  with IntegrationPatience
  with TimeSource {
    this: TestSuite =>

  implicit val eif = uk.gov.hmrc.workitem.ExampleItem.formats

  val appConf = ConfigFactory.load("application.test.conf")

  def exampleItemRepository(collectionName: String) =
    new WorkItemRepository[ExampleItem, ObjectId](
      collectionName = collectionName,
      mongoComponent = mongoComponent,
      itemFormat     = WorkItem.workItemMongoFormat[ExampleItem],
      config         = appConf,
      workItemFields = WorkItemFieldNames(
                         id           = "_id",
                         receivedAt   = "receivedAt",
                         updatedAt    = "updatedAt",
                         availableAt  = "availableAt",
                         status       = "status",
                         failureCount = "failureCount",
                       )
    ) {

    override lazy val inProgressRetryAfter: Duration = Duration.ofHours(1)

    def inProgressRetryAfterProperty: String = "retryAfterSeconds"

    def now(): Instant = timeSource.now
  }

  override lazy val collectionName = "items"

  override lazy val repository = exampleItemRepository(collectionName)

  val item1 = ExampleItem("id1")
  val item2 = item1.copy(id = "id2")
  val item3 = item1.copy(id = "id3")
  val item4 = item1.copy(id = "id4")
  val item5 = item1.copy(id = "id5")
  val item6 = item1.copy(id = "id6")

  val allItems = Seq(item1, item2, item3, item4, item5, item6)
}

case class ExampleItem(id: String)

object ExampleItem {
  implicit val formats = Json.format[ExampleItem]
}


case class ExampleItemWithModule(
  _id      : ObjectId,
  updatedAt: Instant,
  value    : String
)

object ExampleItemWithModule {
  implicit val formats = {
    implicit val instantReads = uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.instantFormats
    implicit val objectIdFormats = uk.gov.hmrc.mongo.play.json.formats.MongoFormats.objectIdFormats
    Json.format[ExampleItemWithModule]
  }
}
