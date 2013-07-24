package com.janrain.backplane.server2.dao

import com.janrain.backplane.dao.redis.{MessageProcessorDaoSupport, RedisMessageDao}
import com.janrain.backplane.server2.oauth2.model._
import com.janrain.backplane.dao.{PasswordHasherDao, ExpiringDao}
import com.janrain.backplane.server2.model._
import com.janrain.backplane.server2.dao.redis.RedisBackplaneMessageDao

/**
 * @author Johnny Bufu
 */
object BP2DAOs {

  val authSessionDao: AuthSessionDao = new RedisMessageDao[AuthSession]("bp2AuthSession:")
    with AuthSessionDao
    with ExpiringDao[AuthSession] {
    protected def instantiate(data: Map[_, _]) = new AuthSession(data.map(kv => kv._1.toString -> kv._2.toString ))
    val expireSeconds = 3600 // 1h
  }

  val authorizationRequestDao: AuthorizationRequestDao = new RedisMessageDao[AuthorizationRequest]("bp2AuthorizationRequest:")
    with AuthorizationRequestDao
    with ExpiringDao[AuthorizationRequest] {
    protected def instantiate(data: Map[_, _]) = new AuthorizationRequest(data.map(kv => kv._1.toString -> kv._2.toString ))

    val expireSeconds = 300 // 5min
  }

  val authorizationDecisionKeyDao: AuthorizationDecisionKeyDao = new RedisMessageDao[AuthorizationDecisionKey]("bp2AuthorizationDecisionKey:")
    with AuthorizationDecisionKeyDao
    with ExpiringDao[AuthorizationDecisionKey] {
    protected def instantiate(data: Map[_, _]) = new AuthorizationDecisionKey(data.map(kv => kv._1.toString -> kv._2.toString ))

    val expireSeconds = 300 // 5min
  }

  val busDao: BusDao = new RedisMessageDao[BusConfig2]("bp2Bus:") with BusDao {
    protected def instantiate(data: Map[_, _]) = new BusConfig2( data.map( kv => kv._1.toString -> kv._2.toString ))

    override def delete(id: String): Boolean = {
      val busDeleteSuccess = super.delete(id)
      grantDao.deleteByBus(List(id)) // throws
      busDeleteSuccess
    }
  }

  val busOwnerDao: BusOwnerDao = new RedisMessageDao[BusOwner]("bp2BusOwner:") with BusOwnerDao with PasswordHasherDao[BusOwnerFields.EnumVal,BusOwner] {
    protected def instantiate(data: Map[_, _]) = new BusOwner( data.map( kv => kv._1.toString -> kv._2.toString ))

    override def delete(id: String): Boolean = {
      val busOwnerDeleteSuccess = super.delete(id)
      busDao.deleteByOwner(id) // throws if not success
      busOwnerDeleteSuccess
    }
  }

  val clientDao: ClientDao = new RedisMessageDao[Client]("bp2Client:") with ClientDao with PasswordHasherDao[ClientFields.EnumVal,Client] {
    protected def instantiate(data: Map[_, _]) = new Client( data.map( kv => kv._1.toString -> kv._2.toString ))
  }

  val grantDao: GrantDao = new RedisMessageDao[Grant]("bp2Grant:") with GrantDao {
    protected def instantiate(data: Map[_, _]) = new Grant( data.map( kv => kv._1.toString -> kv._2.toString ))
  }

  val tokenDao: TokenDao = new RedisMessageDao[Token]("bp2Token:") with TokenDao
    with LegacyDaoForwarder[com.janrain.backplane2.server.Token, Token] {

    protected def instantiate(data: Map[_, _]) = new Token( data.map( kv => kv._1.toString -> kv._2.toString ))

    val legacyDao = com.janrain.backplane2.server.dao.BP2DAOs.getTokenDao

    def preferLegacyGet(id: String) = id.length == Token.LEGACY_TOKEN_LENGTH
  }

  val channelDao: ChannelDao = new RedisMessageDao[Channel]("bp2Channel:") with ChannelDao
    with LegacyDaoForwarder[com.janrain.backplane2.server.Channel, Channel] {

    protected def instantiate(data: Map[_, _]) = new Channel( data.map( kv => kv._1.toString -> kv._2.toString ))

    val legacyDao = com.janrain.backplane2.server.dao.BP2DAOs.getChannelDao

    def preferLegacyGet(id: String) = id.length == Channel.CHANNEL_LEGACY_NAME_LENGTH
  }

  type BackplaneMessageDaoWithProcessor = BackplaneMessageDao with MessageProcessorDaoSupport[BackplaneMessageFields.EnumVal,BackplaneMessage]

  val messageDao: BackplaneMessageDaoWithProcessor = RedisBackplaneMessageDao

}
