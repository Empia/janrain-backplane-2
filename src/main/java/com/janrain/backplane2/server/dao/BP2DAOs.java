package com.janrain.backplane2.server.dao;

import com.janrain.backplane.dao.DAO;
import com.janrain.backplane2.server.config.BusConfig2;
import com.janrain.backplane2.server.config.Client;
import com.janrain.backplane2.server.config.User;
import com.janrain.backplane2.server.dao.redis.*;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * @author Tom Raney
 */

@Service(value="redisDaoFactory")
@Scope(value="singleton")
public class BP2DAOs {

    public static BusDAO getBusDao() {
        return busDao;
    }

    public static ChannelDAO getChannelDao() {
        return channelDao;
    }

    public static TokenDAO getTokenDao() {
        return tokenDao;
    }

    public static GrantDAO getGrantDao() {
        return grantDao;
    }

    public static BusOwnerDAO getBusOwnerDAO() {
        return busOwnerDao;
    }

    public static ClientDAO getClientDAO() {
        return clientDao;
    }

    public static BackplaneMessageDAO getBackplaneMessageDAO() {
        return messageDao;
    }

    public static DAO getDaoByObjectType(Class<?> obj) {
        if (Client.class.isAssignableFrom(obj)) {
            return getClientDAO();
        } else if (User.class.isAssignableFrom(obj)) {
            return getBusOwnerDAO();
        } else if (BusConfig2.class.isAssignableFrom(obj)) {
            return getBusDao();
        }

        return null;
    }

    // - PRIVATE

    private static final TokenDAO tokenDao = new RedisTokenDAO();
    private static final GrantDAO grantDao = new RedisGrantDAO(tokenDao);
    private static final BusDAO busDao = new RedisBusDAO(grantDao);
    private static final ChannelDAO channelDao = new RedisChannelDAO();
    private static final BusOwnerDAO busOwnerDao = new RedisBusOwnerDAO(busDao);
    private static final ClientDAO clientDao = new RedisClientDAO();
    private static final BackplaneMessageDAO messageDao = new RedisBackplaneMessageDAO();

}
