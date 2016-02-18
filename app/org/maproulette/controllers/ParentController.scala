package org.maproulette.controllers

import org.maproulette.data.BaseObject
import org.maproulette.data.dal.ParentDAL
import org.maproulette.utils.Utils
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{BodyParsers, Action}

/**
  * @author cuthbertm
  */
trait ParentController[T<:BaseObject[Long], C<:BaseObject[Long]] extends CRUDController[T] {
  override protected val dal: ParentDAL[Long, T, C]
  protected val childController:CRUDController[C]
  override implicit val tReads: Reads[T]
  override implicit val tWrites: Writes[T]
  protected val cReads:Reads[C]
  protected val cWrites:Writes[C]

  /**
    * Function can be implemented to extract more information than just the default create data,
    * to build other objects with the current object at the core. No data will be returned from this
    * function, it purely does work in the background AFTER creating the current object
    *
    * @param body          The Json body of data
    * @param createdObject The object that was created by the create function
    */
  override def extractAndCreate(body: JsValue, createdObject: T): Unit = {
    implicit val reads:Reads[C] = cReads
    (body \ "children").asOpt[List[JsValue]] match {
      case Some(children) => children map { child =>
        // add the parent id to the child.
        child.transform(parentAddition(createdObject.id)) match {
          case JsSuccess(value, _) =>
            (value \ "id").asOpt[Long] match {
              case Some(identifier) => childController.internalUpdate(value)(identifier)
              case None => Utils.insertJsonID(value).validate[C].fold(
                errors => {
                  throw new Exception(JsError.toJson(errors).toString)
                },
                element => {
                  try {
                    childController.internalCreate(value, element)
                  } catch {
                    case e:Exception =>
                      Logger.error(e.getMessage, e)
                      throw e
                  }
                }
              )
            }
          case JsError(errors) =>
            Logger.error(JsError.toJson(errors).toString)
            throw new Exception(JsError.toJson(errors).toString)
        }
      }
      case None => // ignore
    }
  }

  def parentAddition(id:Long) = {
    __.json.update(
      __.read[JsObject] map { o => o ++ Json.obj("parent" -> Json.toJson(id)) }
    )
  }

  def childrenAddition(children:List[C]) = {
    implicit val writes:Writes[C] = cWrites
    __.json.update(
      __.read[JsObject] map { o => o ++ Json.obj("children" -> Json.toJson(children)) }
    )
  }

  def createChildren(implicit id:Long) = Action(BodyParsers.parse.json) { implicit request =>
    dal.retrieveById match {
      case Some(parent) =>
        extractAndCreate(Json.obj("children" -> request.body), parent)
        Created
      case None =>
        val message = s"Bad id, no parent found with supplied id [$id]"
        Logger.error(message)
        BadRequest(Json.obj("status" -> "KO", "message" -> message))
    }
  }

  def updateChildren(implicit id:Long) = createChildren

  def listChildren(id:Long, limit:Int, offset:Int) = Action {
    implicit val writes:Writes[C] = cWrites
    try {
      Ok(Json.toJson(dal.listChildren(limit, offset)(id)))
    } catch {
      case e:Exception =>
        Logger.error(e.getMessage, e)
        InternalServerError(Json.obj("status" -> "KO", "message" -> e.getMessage))
    }
  }

  def expandedList(id:Long, limit:Int, offset:Int) = Action {
    implicit val writes:Writes[C] = cWrites
    try {
      // first get the parent
      val parent = Json.toJson(dal.retrieveById(id))
      // now list the children
      val children = dal.listChildren(limit, offset)(id)
      // now replace the parent field in the parent with a children array
      parent.transform(childrenAddition(children)) match {
        case JsSuccess(value, _) => Ok(value)
        case JsError(errors) =>
          Logger.error(JsError.toJson(errors).toString)
          InternalServerError(Json.obj("status" -> "KO", "message" -> JsError.toJson(errors)))
      }
    } catch {
      case e:Exception =>
        Logger.error(e.getMessage, e)
        InternalServerError(Json.obj("status" -> "KO", "message" -> e.getMessage))
    }
  }
}
