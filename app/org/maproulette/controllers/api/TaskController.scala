/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.controllers.api

import java.sql.Connection

import akka.util.ByteString
import javax.inject.Inject
import org.locationtech.jts.geom.Envelope
import org.maproulette.Config
import org.maproulette.controllers.CRUDController
import org.maproulette.data._
import org.maproulette.exception.{
  InvalidException,
  LockedException,
  NotFoundException,
  StatusMessage
}
import org.maproulette.framework.model.{Challenge, Comment, Tag, User}
import org.maproulette.framework.service.{ServiceManager, TagService}
import org.maproulette.models._
import org.maproulette.models.dal.mixin.TagDALMixin
import org.maproulette.models.dal.{DALManager, TaskDAL}
import org.maproulette.provider.osm._
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}
import org.maproulette.session.{
  SearchChallengeParameters,
  SearchLocation,
  SearchParameters,
  SessionManager
}
import org.maproulette.utils.Utils
import org.wololo.geojson.{FeatureCollection, GeoJSONFactory}
import org.wololo.jts2geojson.GeoJSONReader
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}

/**
  * The Task controller handles all operations for the Task objects.
  * This includes CRUD operations and searching/listing.
  * See {@link org.maproulette.controllers.CRUDController} for more details on CRUD object operations
  *
  * @author cuthbertm
  */
class TaskController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val dal: TaskDAL,
    override val tagService: TagService,
    serviceManager: ServiceManager,
    dalManager: DALManager,
    wsClient: WSClient,
    webSocketProvider: WebSocketProvider,
    config: Config,
    components: ControllerComponents,
    changeService: ChangesetProvider,
    override val bodyParsers: PlayBodyParsers
) extends AbstractController(components)
    with CRUDController[Task]
    with TagsMixin[Task] {

  import scala.concurrent.ExecutionContext.Implicits.global

  // json reads for automatically reading Tasks from a posted json body
  override implicit val tReads: Reads[Task] = Task.TaskFormat
  // json writes for automatically writing Tasks to a json body response
  override implicit val tWrites: Writes[Task] = Task.TaskFormat
  // json writes for automatically writing Challenges to a json body response
  implicit val cWrites: Writes[Challenge] = Challenge.writes.challengeWrites

  // The type of object that this controller deals with.
  override implicit val itemType  = TaskType()
  override implicit val tableName = this.dal.tableName
  // json reads for automatically reading Tags from a posted json body
  implicit val tagReads: Reads[Tag]           = Tag.tagReads
  implicit val commentReads: Reads[Comment]   = Comment.reads
  implicit val commentWrites: Writes[Comment] = Comment.writes

  implicit val tagChangeReads           = ChangeObjects.tagChangeReads
  implicit val tagChangeResultWrites    = ChangeObjects.tagChangeResultWrites
  implicit val tagChangeSubmissionReads = ChangeObjects.tagChangeSubmissionReads
  implicit val changeReads              = ChangeObjects.changeReads
  implicit val changeSubmissionReads    = ChangeObjects.changeSubmissionReads

  implicit val taskBundleWrites: Writes[TaskBundle] = TaskBundle.taskBundleWrites

  implicit val pointReviewWrites = ClusteredPoint.pointReviewWrites

  override def dalWithTags: TagDALMixin[Task] = dal

  /**
    * This function allows sub classes to modify the body, primarily this would be used for inserting
    * default elements into the body that shouldn't have to be required to create an object.
    *
    * @param body The incoming body from the request
    * @return
    */
  override def updateCreateBody(body: JsValue, user: User): JsValue = {
    // add a default priority, this will be updated later when the task is created if there are
    // priority rules defined in the challenge parent
    val updatedBody = Utils.insertIntoJson(body, "priority", Challenge.PRIORITY_HIGH)(IntWrites)
    // We need to update the geometries to make sure that we handle all the different types of
    // geometries that you can deal with like WKB or GeoJSON
    this.updateGeometryData(super.updateCreateBody(updatedBody, user))
  }

  /**
    * In the case where you need to update the update body, usually you would not update it, but
    * just in case.
    *
    * @param body The request body
    * @return The updated request body
    */
  override def updateUpdateBody(body: JsValue, user: User): JsValue =
    this.updateGeometryData(super.updateUpdateBody(body, user))

  private def updateGeometryData(body: JsValue): JsValue = {
    val updatedBody = (body \ "geometries").asOpt[String] match {
      case Some(value) =>
        // if it is a string, then it is either GeoJSON or a WKB
        // just check to see if { is the first character and then we can assume it is GeoJSON
        if (value.charAt(0) != '{') {
          // TODO:
          body
        } else {
          // just return the body because it handles this case correctly
          body
        }
      case None =>
        // if it maps to None then it simply could be that it is a JSON object
        (body \ "geometries").asOpt[JsValue] match {
          case Some(value) =>
            // need to convert to a string for the case class otherwise validation will fail
            Utils.insertIntoJson(body, "geometries", value.toString(), true)
          case None =>
            // if the geometries are not supplied then just leave it
            body
        }
    }
    (updatedBody \ "location").asOpt[String] match {
      case Some(value) => updatedBody
      case None =>
        (updatedBody \ "location").asOpt[JsValue] match {
          case Some(value) =>
            Utils.insertIntoJson(updatedBody, "location", value.toString(), true)
          case None => updatedBody
        }
    }
    (updatedBody \ "cooperativeWork").asOpt[String] match {
      case Some(value) => updatedBody
      case None =>
        (updatedBody \ "cooperativeWork").asOpt[JsValue] match {
          case Some(value) =>
            Utils.insertIntoJson(updatedBody, "cooperativeWork", value.toString(), true)
          case None => updatedBody
        }
    }
  }

  /**
    * Function can be implemented to extract more information than just the default create data,
    * to build other objects with the current object at the core. No data will be returned from this
    * function, it purely does work in the background AFTER creating the current object
    *
    * @param body          The Json body of data
    * @param createdObject The object that was created by the create function
    * @param user          The user that is executing the function
    */
  override def extractAndCreate(body: JsValue, createdObject: Task, user: User)(
      implicit c: Option[Connection] = None
  ): Unit = {
    // If we have added a new task to a 'finished' challenge, we need to make
    // sure to set challenge back to 'ready'
    this.dalManager.challenge.updateReadyStatus()(createdObject.parent)
    this.extractTags(body, createdObject, User.superUser, true)
  }

  /**
    * Gets a json list of tags of the task
    *
    * @param id The id of the task containing the tags
    * @return The html Result containing json array of tags
    */
  def getTagsForTask(implicit id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.getTags(id)))
    }
  }

  /**
    * Start on task (lock it). An error will be returned if someone else has the lock.
    *
    * @param taskId     Id of task that you wish to start
    * @return
    */
  def startOnTask(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.dal.retrieveById(taskId) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $taskId not found, unable to lock.")
      }

      val success = this.dal.lockItem(user, task)
      if (success == 0) {
        throw new IllegalAccessException(s"Current task [${taskId}] is locked by another user.")
      }

      webSocketProvider.sendMessage(
        WebSocketMessages.taskClaimed(task, Some(WebSocketMessages.userSummary(user)))
      )
      Ok(Json.toJson(task))
    }
  }

  /**
    * Retrieve cooperative change XML for task
    *
    * @param taskId     Id of task that you wish to start
    * @return
    */
  def cooperativeWorkChangeXML(taskId: Long, filename: String): Action[AnyContent] = Action.async {
    implicit request =>
      val task = this.dal.retrieveById(taskId) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $taskId not found.")
      }

      val xml = task.cooperativeWork match {
        case Some(cw) =>
          val cooperativeWork = Json.parse(cw)
          (cooperativeWork \ "file" \ "content").asOpt[String] match {
            case Some(base64EncodedXML) =>
              new String(java.util.Base64.getDecoder.decode(base64EncodedXML))
            case None => throw new NotFoundException(s"Task $taskId does not offer change XML.")
          }

        case None => throw new NotFoundException(s"Task $taskId does not offer cooperative work.")
      }

      Future {
        Result(
          header = ResponseHeader(
            OK,
            Map(CONTENT_DISPOSITION -> s"attachment; filename=${filename}")
          ),
          body = HttpEntity.Strict(
            ByteString.fromString(xml),
            Some("text/xml")
          )
        )
      }
  }

  /**
    * Releases the task (unlock it).
    *
    * @param taskId    Id of task that you wish to release
    * @return
    */
  def releaseTask(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.dal.retrieveById(taskId) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $taskId not found, unable to lock.")
      }

      try {
        this.dal.unlockItem(user, task)
        webSocketProvider.sendMessage(
          WebSocketMessages.taskReleased(task, Some(WebSocketMessages.userSummary(user)))
        )
      } catch {
        case e: Exception => logger.warn(e.getMessage)
      }

      Ok(Json.toJson(task))
    }
  }

  /**
    * Refresh the active lock on the task, extending its allowed duration
    *
    * @param taskId    Id of the task on which the lock is to be refreshed
    * @return
    */
  def refreshTaskLock(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.dal.retrieveById(taskId) match {
        case Some(t) =>
          try {
            this.dal.refreshItemLock(user, t)
            Ok(Json.toJson(t))
          } catch {
            case e: LockedException => throw new IllegalAccessException(e.getMessage)
          }
        case None =>
          throw new NotFoundException(s"Task with $taskId not found, unable to refresh lock.")
      }
    }
  }

  /**
    * Gets a random task(s) given the provided tags.
    *
    * @param projectSearch   Filter on the name of the project
    * @param challengeSearch Filter on the name of the challenge (Survey included)
    * @param challengeTags   Filter on the tags of the challenge
    * @param tags            A comma separated list of tags to match against
    * @param taskSearch      Filter based on the name of the task
    * @param limit           The number of tasks to return
    * @param proximityId     Id of task that you wish to find the next task based on the proximity of that task
    * @return
    */
  def getRandomTasks(
      projectSearch: String,
      challengeSearch: String,
      challengeTags: String,
      tags: String,
      taskSearch: String,
      limit: Int,
      proximityId: Long
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val params = SearchParameters(
        projectSearch = Some(projectSearch),
        challengeParams = SearchChallengeParameters(
          challengeSearch = Some(challengeSearch),
          challengeTags = Some(challengeTags.split(",").toList)
        ),
        taskTags = Some(tags.split(",").toList),
        taskSearch = Some(taskSearch)
      )
      val result = this.dal.getRandomTasks(
        User.userOrMocked(user),
        params,
        limit,
        None,
        Utils.negativeToOption(proximityId)
      )
      result.map(task => {
        this.actionManager.setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), "")
        this.inject(task)
      })
      Ok(Json.toJson(result))
    }
  }

  /**
    * This injection method will make a call to Mapillary to pull in any matching images that
    * might be useful
    *
    * @param obj the object being sent in the response
    * @return A Json representation of the object
    */
  override def inject(obj: Task)(implicit request: Request[Any]): JsValue = {
    var taskToReturn = obj

    val serverInfo = config.getMapillaryServerInfo
    if (serverInfo.clientId.nonEmpty) {
      if (request.getQueryString("mapillary").getOrElse("false").toBoolean) {
        // build the envelope for the task geometries
        val taskFeatureCollection =
          GeoJSONFactory.create(obj.geometries).asInstanceOf[FeatureCollection]
        val reader   = new GeoJSONReader()
        val envelope = new Envelope()
        taskFeatureCollection.getFeatures.foreach(f => {
          val current = reader.read(f.getGeometry)
          envelope.expandToInclude(current.getEnvelopeInternal)
        })
        // user can provide border information in the query string, so check there first before using the default
        val borderExpansionSize =
          request.getQueryString("border").getOrElse(serverInfo.border.toString).toDouble
        envelope.expandBy(borderExpansionSize)
        val apiReq =
          s"https://${serverInfo.host}/v3/images/?&bbox=${envelope.getMinX},${envelope.getMinY},${envelope.getMaxX},${envelope.getMaxY}&client_id=${serverInfo.clientId}"
        logger.debug(s"Requesting Mapillary image information for: $apiReq")
        val mapFuture         = wsClient.url(apiReq).get()
        val response          = Await.result(mapFuture, 5.seconds)
        val featureCollection = response.json
        val images = (featureCollection \ "features")
          .as[List[JsValue]]
          .map(feature => {
            val key    = (feature \ "properties" \ "key").get.as[String]
            val latlon = (feature \ "geometry" \ "coordinates").as[List[JsNumber]]
            MapillaryImage(
              key,
              latlon.tail.head.as[Double],
              latlon.head.as[Double],
              s"https://d1cuyjsrcm0gby.cloudfront.net/$key/thumb-320.jpg",
              s"https://d1cuyjsrcm0gby.cloudfront.net/$key/thumb-640.jpg",
              s"https://d1cuyjsrcm0gby.cloudfront.net/$key/thumb-1024.jpg",
              s"https://d1cuyjsrcm0gby.cloudfront.net/$key/thumb-2048.jpg"
            )
          })
        taskToReturn = obj.copy(mapillaryImages = Some(images))
      }
    }

    val tags = this.tagService.listByTask(taskToReturn.id)
    Utils.insertIntoJson(Json.toJson(taskToReturn), Tag.TABLE, Json.toJson(tags.map(_.name)))
  }

  /**
    * Gets all the tasks within a bounding box
    *
    * @param left   The minimum latitude for the bounding box
    * @param bottom The minimum longitude for the bounding box
    * @param right  The maximum latitude for the bounding box
    * @param top    The maximum longitude for the bounding box
    * @param limit  Limit for the number of returned tasks
    * @param offset The offset used for paging
    * @return
    */
  def getTasksInBoundingBox(
      left: Double,
      bottom: Double,
      right: Double,
      top: Double,
      limit: Int,
      page: Int,
      excludeLocked: Boolean,
      sort: String = "",
      order: String = "ASC",
      includeTotal: Boolean = false,
      includeGeometries: Boolean = false,
      includeTags: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val params = p.copy(location = Some(SearchLocation(left, bottom, right, top)))
        val (count, result) = this.dalManager.taskCluster.getTasksInBoundingBox(
          User.userOrMocked(user),
          params,
          limit,
          page,
          excludeLocked,
          sort,
          order
        )

        val resultJson = _insertExtraJSON(result, includeGeometries, includeTags)

        if (includeTotal) {
          Ok(Json.obj("total" -> count, "tasks" -> resultJson))
        } else {
          Ok(resultJson)
        }
      }
    }
  }

  /**
    * Fetches and inserts usernames for 'reviewRequestedBy' and 'reviewBy' into
    * the ClusteredPoint.pointReview
    */
  private def _insertExtraJSON(
      tasks: List[ClusteredPoint],
      includeGeometries: Boolean = false,
      includeTags: Boolean = false
  ): JsValue = {
    if (tasks.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val mappers = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.completedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val reviewRequesters = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.pointReview.reviewRequestedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val reviewers = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.pointReview.reviewedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val taskDetailsMap: Map[Long, Task] =
        includeGeometries match {
          case true =>
            val taskDetails = this.dalManager.task.retrieveListById()(tasks.map(t => t.id))
            taskDetails.map(t => t.id -> t).toMap
          case false => null
        }

      val tagsMap: Map[Long, List[Tag]] = includeTags match {
        case true => this.serviceManager.tag.listByTasks(tasks.map(t => t.id))
        case _    => null
      }

      val jsonList = tasks.map { task =>
        var updated         = Json.toJson(task)
        var reviewPointJson = Json.toJson(task.pointReview).as[JsObject]

        if (task.completedBy.getOrElse(0) != 0) {
          val mappersJson = Json.toJson(mappers.get(task.completedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "completedBy", mappersJson, true)
        }

        if (task.pointReview.reviewRequestedBy.getOrElse(0) != 0) {
          val reviewRequestersJson =
            Json.toJson(reviewRequesters.get(task.pointReview.reviewRequestedBy.get)).as[JsObject]
          reviewPointJson = Utils
            .insertIntoJson(reviewPointJson, "reviewRequestedBy", reviewRequestersJson, true)
            .as[JsObject]
          updated = Utils.insertIntoJson(updated, "pointReview", reviewPointJson, true)
        }

        if (task.pointReview.reviewedBy.getOrElse(0) != 0) {
          var reviewerJson =
            Json.toJson(reviewers.get(task.pointReview.reviewedBy.get)).as[JsObject]
          reviewPointJson =
            Utils.insertIntoJson(reviewPointJson, "reviewedBy", reviewerJson, true).as[JsObject]
          updated = Utils.insertIntoJson(updated, "pointReview", reviewPointJson, true)
        }

        if (includeGeometries) {
          val geometries = Json.parse(taskDetailsMap(task.id).geometries)
          updated = Utils.insertIntoJson(updated, "geometries", geometries, true)
        }

        if (includeTags) {
          if (tagsMap.contains(task.id)) {
            val tagsJson = Json.toJson(tagsMap(task.id))
            updated = Utils.insertIntoJson(updated, "tags", tagsJson, true)
          }
        }

        updated
      }
      Json.toJson(jsonList)
    }
  }

  /**
    * This is the generic function that is leveraged by all the specific functions above. So it
    * sets the task status to the specific status ID's provided by those functions.
    * Must be authenticated to perform operation
    *
    * @param id     The id of the task
    * @param status The status id to set the task's status to
    * @param comment An optional comment to add to the task
    * @param tags Optional tags to add to the task
    * @return 400 BadRequest if status id is invalid or task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setTaskStatus(
      id: Long,
      status: Int,
      comment: String = "",
      tags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val requestReview = request.getQueryString("requestReview") match {
        case Some(v) => Some(v.toBoolean)
        case None    => None
      }

      val completionResponses = request.body.asJson
      this.customTaskStatus(
        id,
        TaskStatusSet(status),
        user,
        comment,
        tags,
        requestReview,
        completionResponses
      )

      NoContent
    }
  }

  /**
    * This function sets the task review status.
    * Must be authenticated to perform operation and marked as a reviewer.
    *
    * @param id The id of the task
    * @param reviewStatus The review status id to set the task's review status to
    * @param comment An optional comment to add to the task
    * @param tags Optional tags to add to the task
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setTaskReviewStatus(
      id: Long,
      reviewStatus: Int,
      comment: String = "",
      tags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.dal.retrieveById(id) match {
        case Some(t) => t
        case None =>
          throw new NotFoundException(s"Task with $id not found, cannot set review status.")
      }

      val action = this.actionManager
        .setAction(Some(user), new TaskItem(task.id), TaskReviewStatusSet(reviewStatus), task.name)
      val actionId = action match {
        case Some(a) => Some(a.id)
        case None    => None
      }

      this.dalManager.taskReview.setTaskReviewStatus(task, reviewStatus, user, actionId, comment)

      val tagList = tags.split(",").toList
      if (tagList.nonEmpty) {
        this.addTagstoItem(id, tagList.map(new Tag(-1, _, tagType = this.dal.tableName)), user)
      }

      NoContent
    }
  }

  /**
    * This function will set the review status to "Unnecessary", essentially removing the
    * review request.
    *
    * User must have write access to parent challenge(s).
    *
    * @param ids The ids of the tasks to update
    * @return The number of tasks updated.
    */
  def removeReviewRequest(ids: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      SearchParameters.withSearch { p =>
        implicit val taskIds = Utils.toLongList(ids) match {
          case Some(l) if !l.isEmpty => l
          case None => {
            val params = p.location match {
              case Some(l) => p
              case None    =>
                // No bounding box, so search everything
                p.copy(location = Some(SearchLocation(-180, -90, 180, 90)))
            }
            val (count, tasks) = this.dalManager.taskCluster.getTasksInBoundingBox(user, params, -1)
            tasks.map(task => task.id)
          }
        }

        // set the taskIds variable to `implicit` above
        val updatedTasks = this.dal
          .retrieveListById()
          .foldLeft(0)((updatedCount, t) =>
            t.review.reviewStatus match {
              case Some(r) =>
                updatedCount +
                  this.dalManager.taskReview
                    .setTaskReviewStatus(t, Task.REVIEW_STATUS_UNNECESSARY, user, None, "")
              case None => updatedCount
            }
          )

        Ok(Json.toJson(updatedTasks))
      }
    }
  }

  /**
    * Changes the status on tasks that meet the search criteria (SearchParameters)
    *
    * @param newStatus The status to change all the tasks to
    * @return The number of tasks changed.
    */
  def bulkStatusChange(newStatus: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      SearchParameters.withSearch { p =>
        var params = p
        params.location match {
          case Some(l) => // do nothing, already have bounding box
          case None    =>
            // No bounding box, so search everything
            params = p.copy(location = Some(SearchLocation(-180, -90, 180, 90)))
        }
        val (count, tasks) = this.dalManager.taskCluster.getTasksInBoundingBox(user, params, -1)
        tasks.foreach(task => {
          val taskJson = Json.obj("id" -> task.id, "status" -> newStatus)
          this.dal.update(taskJson, user)(task.id)
        })
        Ok(Json.toJson(tasks.length))
      }
    }
  }

  /**
    * Matches the task to a OSM Changeset, this will only
    *
    * @param taskId the id for the task
    * @return The new Task object
    */
  def matchToOSMChangeSet(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedFutureRequest { implicit user =>
      this.dal.retrieveById(taskId) match {
        case Some(t) =>
          val promise = Promise[Result]
          this.dal.matchToOSMChangeSet(t, user, false) onComplete {
            case Success(response) => promise success Ok(Json.toJson(t))
            case Failure(error)    => promise failure error
          }
          promise.future
        case None => throw new NotFoundException("Task not found to update taskId with")
      }
    }
  }

  /**
    * Gets clusters of tasks for the challenge. Uses kmeans method in postgis.
    *
    * @param numberOfPoints Number of clustered points you wish to have returned
    * @return A list of ClusteredPoint's that represent clusters of tasks
    */
  def getTaskClusters(numberOfPoints: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(Json.toJson(this.dalManager.taskCluster.getTaskClusters(params, numberOfPoints)))
      }
    }
  }

  /**
    * Gets the list of tasks that are contained within the single cluster
    *
    * @param clusterId      The cluster id, when "getTaskClusters" is executed it will return single point clusters
    *                       representing all the tasks in the cluster. Each cluster will contain an id, supplying
    *                       that id to this method will allow you to retrieve all the tasks in the cluster
    * @param numberOfPoints Number of clustered points that was originally used to get all the clusters
    * @return A list of ClusteredPoint's that represent each of the tasks within a single cluster
    */
  def getTasksInCluster(clusterId: Int, numberOfPoints: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        SearchParameters.withSearch { implicit params =>
          Ok(
            Json.toJson(
              this.dalManager.taskCluster.getTasksInCluster(clusterId, params, numberOfPoints)
            )
          )
        }
      }
  }

  def applyTagFix(taskId: Long, comment: String = "", tags: String = ""): Action[JsValue] =
    Action.async(bodyParsers.json) { implicit request =>
      this.sessionManager.authenticatedFutureRequest { implicit user =>
        val result = request.body.validate[TagChangeSubmission]
        result.fold(
          errors => {
            Future {
              BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
            }
          },
          element => {
            val p = Promise[Result]

            val requestReview = request.getQueryString("requestReview") match {
              case Some(v) => Some(v.toBoolean)
              case None    => None
            }

            // Convert tag changes to OSMChange object
            val updates = element.changes.map(tagChange => {
              ElementUpdate(
                tagChange.osmId,
                tagChange.osmType,
                tagChange.version,
                ElementTagChange(tagChange.updates, tagChange.deletes)
              )
            })
            val change = OSMChange(None, Some(updates))

            config.skipOSMChangesetSubmission match {
              // If we are skipping the OSM submission then we don't actually do the tag change on OSM
              case true =>
                this.customTaskStatus(
                  taskId,
                  TaskStatusSet(Task.STATUS_FIXED),
                  user,
                  comment,
                  tags,
                  requestReview
                )
                p success Ok(Json.toJson(true))
              case _ =>
                None
                changeService.submitOsmChange(
                  change,
                  element.comment,
                  user.osmProfile.requestToken,
                  Some(taskId)
                ) onComplete {
                  case Success(res) => {
                    this.customTaskStatus(
                      taskId,
                      TaskStatusSet(Task.STATUS_FIXED),
                      user,
                      comment,
                      tags,
                      requestReview
                    )
                    p success Ok(res)
                  }
                  case Failure(f) => p failure f
                }
            }
            p.future
          }
        )
      }
    }

  def customTaskStatus(
      taskId: Long,
      actionType: ActionType,
      user: User,
      comment: String = "",
      tags: String = "",
      requestReview: Option[Boolean] = None,
      completionResponses: Option[JsValue] = None
  ) = {
    val status = actionType match {
      case t: TaskStatusSet    => t.status
      case q: QuestionAnswered => Task.STATUS_ANSWERED
      case _                   => Task.STATUS_CREATED
    }

    if (!Task.isValidStatus(status)) {
      throw new InvalidException(s"Cannot set task [$taskId] to invalid status [$status]")
    }
    val task = this.dal.retrieveById(taskId) match {
      case Some(t) => t
      case None    => throw new NotFoundException(s"Task with $taskId not found, can not set status.")
    }

    this.dal.setTaskStatus(List(task), status, user, requestReview, completionResponses)

    val action =
      this.actionManager.setAction(Some(user), new TaskItem(task.id), actionType, task.name)
    // add comment if any provided
    if (comment.nonEmpty) {
      val actionId = action match {
        case Some(a) => Some(a.id)
        case None    => None
      }
      this.serviceManager.comment.create(user, task.id, comment, actionId)
    }

    val tagList = if (tags == "") List() else tags.split(",").toList
    if (tagList.nonEmpty) {
      this.addTagstoItem(taskId, tagList.map(new Tag(-1, _, tagType = this.dal.tableName)), user)
    }
  }
}
