package com.janrain.backplane.server1.model

import com.janrain.backplane.common.model.{UserFieldEnum, User}
import com.janrain.backplane.config.model.Password
import com.janrain.backplane.server2.dao.LegacySupport
import scala.collection.JavaConversions._

/**
 * @author Johnny Bufu
 */
class BusUser(data: Map[String,String]) extends User("bp1User", data, BusUserFields.values)
  with Password[BusUserFields.EnumVal, BusUser]
  with LegacySupport[com.janrain.backplane2.server.config.User] {

  def this(username: String, pwdhash: String) = this(Map(
    BusUserFields.USER.name -> username,
    BusUserFields.PWDHASH.name -> pwdhash
  ))

  def idField = BusUserFields.USER

  def pwdHashField = BusUserFields.PWDHASH

  def asLegacy = new com.janrain.backplane2.server.config.User(mapAsJavaMap(this))
}

object BusUserFields extends UserFieldEnum {
  trait BusUserEnumVal extends EnumVal
  // no new, BusUser-specific fields
}