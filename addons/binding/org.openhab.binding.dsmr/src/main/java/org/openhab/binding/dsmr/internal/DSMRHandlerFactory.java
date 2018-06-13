/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.internal;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.openhab.binding.dsmr.DSMRBindingConstants;
import org.openhab.binding.dsmr.handler.DSMRBridgeHandler;
import org.openhab.binding.dsmr.handler.DSMRMeterHandler;
import org.openhab.binding.dsmr.internal.discovery.DSMRMeterDiscoveryService;
import org.openhab.binding.dsmr.internal.meter.DSMRMeterType;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DSMRHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author M. Volaart - Initial contribution
 */
@Component(service = ThingHandlerFactory.class, configurationPid = "binding.dsmr")
public class DSMRHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(DSMRHandlerFactory.class);

    private final Map<ThingUID, @Nullable ServiceRegistration<?>> discoveryServiceRegs = new HashMap<>();

    /**
     * Returns if the specified ThingTypeUID is supported by this handler.
     *
     * This handler support the THING_TYPE_DSMR_BRIDGE type and all ThingTypesUID that
     * belongs to the supported DSMRMeterType objects
     *
     * @param {@link ThingTypeUID} to check
     * @return true if the specified ThingTypeUID is supported, false otherwise
     */
    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        if (thingTypeUID.equals(DSMRBindingConstants.THING_TYPE_DSMR_BRIDGE)) {
            logger.debug("DSMR Bridge Thing {} supported", thingTypeUID);
            return true;
        } else {
            boolean thingTypeUIDIsMeter = DSMRMeterType.METER_THING_TYPES.contains(thingTypeUID);
            if (thingTypeUIDIsMeter) {
                logger.trace("{} is a supported DSMR Meter thing", thingTypeUID);
            }
            return thingTypeUIDIsMeter;
        }
    }

    /**
     * Create the ThingHandler for the corresponding Thing
     *
     * There are two handlers supported:
     * - DSMRBridgeHandler that handle the Thing that corresponds to the physical DSMR device and does the serial
     * communication
     * - MeterHandler that handles the Meter things that are a logical part of the physical device
     *
     * @param thing The Thing to create a ThingHandler for
     * @return ThingHandler for the given Thing or null if the Thing is not supported
     */
    @Override
    protected ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        logger.debug("Searching for thingTypeUID {}", thingTypeUID);
        if (DSMRBindingConstants.THING_TYPE_DSMR_BRIDGE.equals(thingTypeUID)) {
            DSMRBridgeHandler handler = new DSMRBridgeHandler((Bridge) thing);
            registerLightDiscoveryService(handler);
            return handler;
        } else if (DSMRMeterType.METER_THING_TYPES.contains(thingTypeUID)) {
            return new DSMRMeterHandler(thing);
        }

        return null;
    }

    private synchronized void registerLightDiscoveryService(DSMRBridgeHandler bridgeHandler) {
        DSMRMeterDiscoveryService discoveryService = new DSMRMeterDiscoveryService(bridgeHandler);

        this.discoveryServiceRegs.put(bridgeHandler.getThing().getUID(), bundleContext
                .registerService(DiscoveryService.class.getName(), discoveryService, new Hashtable<String, Object>()));
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof DSMRBridgeHandler) {
            ServiceRegistration<?> serviceReg = this.discoveryServiceRegs.get(thingHandler.getThing().getUID());
            if (serviceReg != null) {
                // remove discovery service, if bridge handler is removed
                serviceReg.unregister();
                discoveryServiceRegs.remove(thingHandler.getThing().getUID());
            }
        }
    }
}