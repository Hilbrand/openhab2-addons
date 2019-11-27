/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smappee.internal.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The result of a smappee reading
 *
 * @author Niko Tanghe - Initial contribution
 */
public class SmappeeDeviceReading {

    public int serviceLocationId;
    public SmappeeDeviceReadingConsumption[] consumptions;

    public double getLatestConsumption() {
        if (consumptions.length == 0) {
            return 0;
        }
        return round(getLatestReading().consumption, 0);
    }

    public double getLatestSolar() {
        if (consumptions.length == 0) {
            return 0;
        }
        return round(getLatestReading().solar, 0);
    }

    public double getLatestAlwaysOn() {
        if (consumptions.length == 0) {
            return 0;
        }
        return round(getLatestReading().alwaysOn, 0);
    }

    private SmappeeDeviceReadingConsumption getLatestReading() {
        SmappeeDeviceReadingConsumption latestReading = consumptions[0];

        for (SmappeeDeviceReadingConsumption reading : consumptions) {
            if (reading.timestamp > latestReading.timestamp) {
                latestReading = reading;
            }
        }
        return latestReading;
    }

    private double round(double value, int places) {
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
}
