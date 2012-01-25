package com.janrain.backplane2.server;

import com.janrain.commons.supersimpledb.message.MessageField;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * @author Tom Raney
 */
public abstract class Base extends Access {

    /**
     * Empty default constructor for AWS to use.
     * Don't call directly.
     */
    public Base() {};

    public Base(String id, String buses, Date expires) {
        super(id, expires);

        if (StringUtils.isNotEmpty(buses)) {
            put(BaseField.BUSES.getFieldName(), buses);
        }
    }

    public @Nullable
    String getBusesAsString() {
        return this.get(BaseField.BUSES);
    }

    /**
     * Retrieve list of authorized buses
     * @return a valid list which may be empty
     */
    public @NotNull
    List<String> getBusesAsList() {
        String busesAsString = getBusesAsString();
        if (StringUtils.isEmpty(busesAsString)) {
            return new ArrayList<String>();
        } else {
            return Arrays.asList(busesAsString.split(" "));
        }
    }

    public boolean isAllowedBus(@NotNull String testBus) {
        return getBusesAsList().contains(testBus);
    }

    public boolean isAllowedBuses(@NotNull List<String> testBuses) {
        return getBusesAsList().containsAll(testBuses);
    }

    /**
     * Retrieve an encoded space delimited string of authorized buses
     * as "bus:thisbus.com bus:andthatbus.com ..."
     * @return
     */

    public String getEncodedBusesAsString() {
        StringBuilder sb = new StringBuilder();
        for (String bus: getBusesAsList()) {
            sb.append("bus:" + bus + " ");
        }
        return sb.toString().trim();
    }

    public static enum BaseField implements MessageField {

        // - PUBLIC

        BUSES("buses", true);

        @Override
        public String getFieldName() {
            return fieldName;
        }

        @Override
        public boolean isRequired() {
            return required;
        }

        @Override
        public void validate(String value) throws RuntimeException {
            if (isRequired()) validateNotNull(getFieldName(), value);
        }

        // - PRIVATE

        private String fieldName;
        private boolean required = true;

        private BaseField(String fieldName) {
            this(fieldName, true);
        }

        private BaseField(String fieldName, boolean required) {
            this.fieldName = fieldName;
            this.required = required;
        }
    }

    private static final Logger logger = Logger.getLogger(Access.class);
}