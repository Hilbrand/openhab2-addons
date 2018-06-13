/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.internal.discovery;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.dsmr.internal.device.DSMRDeviceConstants;
import org.openhab.binding.dsmr.internal.meter.DSMRMeterConstants;
import org.openhab.binding.dsmr.internal.meter.DSMRMeterDescriptor;
import org.openhab.binding.dsmr.internal.meter.DSMRMeterType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This implements the discovery service for new DSMR Meters
 *
 * @author M. Volaart - Initial contribution
 */
@NonNullByDefault
public abstract class DSMRDiscoveryService extends AbstractDiscoveryService {
    private final Logger logger = LoggerFactory.getLogger(DSMRDiscoveryService.class);

    protected final DSMRMeterDetector meterDetector = new DSMRMeterDetector();

    /**
     * Constructs a new DSMRMeterDiscoveryService with the specified DSMR Bridge ThingUID
     */
    public DSMRDiscoveryService() {
        super(DSMRMeterType.METER_THING_TYPES, DSMRDeviceConstants.DSMR_DISCOVERY_TIMEOUT_SECONDS, false);
    }

    /**
     * Callback when a new meter is discovered
     * The new meter is described by the {@link DSMRMeterDescriptor}
     *
     * There will be a DiscoveryResult created and sent to the framework.
     *
     * At this moment there are no reasons why a new meter will not be accepted.
     *
     * Therefore this callback will always return true.
     *
     * @param meterDescriptor the descriptor of the new detected meter
     * @param dsmrBridgeUID ThingUID for the DSMR Bridges
     * @return true (meter is always accepted)
     */
    public boolean meterDiscovered(DSMRMeterDescriptor meterDescriptor, ThingUID dsmrBridgeUID) {
        DSMRMeterType meterType = meterDescriptor.getMeterType();
        ThingTypeUID thingTypeUID = meterType.getThingTypeUID();
        String thingId = "dsmr:" + meterType.name().toLowerCase() + ":"
                + (meterDescriptor.getChannel() == DSMRMeterConstants.UNKNOWN_CHANNEL ? "default"
                        : meterDescriptor.getChannel());
        ThingUID thingUID = new ThingUID(thingId);

        // Construct the configuration for this meter
        Map<String, Object> properties = new HashMap<>();
        properties.put("meterType", meterType.name());
        properties.put("channel", meterDescriptor.getChannel());

        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withThingType(thingTypeUID)
                .withBridge(dsmrBridgeUID).withProperties(properties).withLabel(meterType.meterKind.toString()).build();

        logger.debug("{} for meterDescriptor {}", discoveryResult, meterDescriptor);
        thingDiscovered(discoveryResult);

        return true;
    }
}