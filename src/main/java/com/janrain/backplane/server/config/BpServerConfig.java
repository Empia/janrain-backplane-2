package com.janrain.backplane.server.config;

import com.janrain.commons.supersimpledb.SimpleDBException;
import com.janrain.commons.supersimpledb.message.AbstractMessage;
import com.janrain.commons.supersimpledb.message.MessageField;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Tom Raney
 */
public class BpServerConfig extends AbstractMessage implements Externalizable {

    public final static String BPSERVER_CONFIG_KEY = "bpserverconfig";

    public BpServerConfig() {
        Map<String,String> d = new LinkedHashMap<String, String>();

        d.put(Field.DEBUG_MODE.getFieldName(), Field.DEBUG_MODE_DEFAULT.toString() );
        d.put(Field.CLEANUP_INTERVAL_MINUTES.getFieldName(), Long.toString(Field.CLEANUP_INTERVAL_MINUTES_DEFAULT));
        d.put(Field.DEFAULT_MESSAGES_MAX.getFieldName(), Long.toString(Field.MESSAGES_MAX_DEFAULT));
        d.put(Field.CONFIG_CACHE_AGE_SECONDS.getFieldName(), Long.toString(Field.CONFIG_CACHE_AGE_SECONDS_DEFAULT));

        try {
            super.init("foo", d);
        } catch (SimpleDBException e) {
            logger.error(e);
        }

    }

    public long getMaxCacheAge() {
        return Long.parseLong(get(Field.CONFIG_CACHE_AGE_SECONDS));
    }

    @Override
    public String getIdValue() {
        return "foo";
    }

    @Override
    public Set<? extends MessageField> getFields() {
        return EnumSet.allOf(Field.class);
    }

    @Override
    public void writeExternal(ObjectOutput objectOutput) throws IOException {

    }

    @Override
    public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {

    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = null;
        byte[] bytes = null;
        try {
            oos = new ObjectOutputStream(bos);
            oos.writeObject(this);
            oos.flush();
            bytes = bos.toByteArray();
        } catch (IOException e) {
            logger.error(e);
        } finally {
            try {
                if (oos != null) {
                    oos.close();
                }
                bos.close();
            } catch (IOException e) {
                logger.error(e);
            }
        }
        return bytes;
    }

    public static BpServerConfig fromBytes(byte[] bytes) {

        if (bytes == null) {
            return null;
        }

        ObjectInputStream in = null;
        try {
            in = new ObjectInputStream(new ByteArrayInputStream(bytes));
            return (BpServerConfig) in.readObject();
        } catch (Exception e1) {
            logger.error(e1);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                logger.error(e);
            }
        }
        return null;
    }


    public static enum Field implements MessageField {

        ID,
        DEBUG_MODE,
        CONFIG_CACHE_AGE_SECONDS,
        CLEANUP_INTERVAL_MINUTES,
        DEFAULT_MESSAGES_MAX;

        @Override
        public String getFieldName() {
            return name();
        }

        @Override
        public boolean isRequired() {
            return true;
        }

        @Override
        public void validate(String value) throws SimpleDBException {
            if (isRequired()) validateNotBlank(name(), value);
        }

        // PRIVATE

        private static final Boolean DEBUG_MODE_DEFAULT = true;
        private static final long CONFIG_CACHE_AGE_SECONDS_DEFAULT = 10;
        private static final long CLEANUP_INTERVAL_MINUTES_DEFAULT = 2;
        private static final long MESSAGES_MAX_DEFAULT = 50;
        private static final long MESSAGE_CACHE_MAX_MB_DEFAULT = 0;
        private static final long TOKEN_CACHE_MAX_MB_DEFAULT = 100;


    }

    // PRIVATE

    private static final long serialVersionUID = -1845727748278295636L;

    private static final Logger logger = Logger.getLogger(BpServerConfig.class);
}