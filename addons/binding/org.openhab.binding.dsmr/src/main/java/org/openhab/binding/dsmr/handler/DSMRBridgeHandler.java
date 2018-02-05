/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.dsmr.internal.device.DSMRAutoConfigDevice;
import org.openhab.binding.dsmr.internal.device.DSMRDevice;
import org.openhab.binding.dsmr.internal.device.DSMRDeviceConfiguration;
import org.openhab.binding.dsmr.internal.device.DSMRDeviceConstants;
import org.openhab.binding.dsmr.internal.device.DSMRFixedConfigDevice;
import org.openhab.binding.dsmr.internal.device.DSMRPortEventListener;
import org.openhab.binding.dsmr.internal.device.cosem.CosemObject;
import org.openhab.binding.dsmr.internal.device.serial.DSMRPortEvent;
import org.openhab.binding.dsmr.internal.device.serial.DSMRPortSettings;
import org.openhab.binding.dsmr.internal.meter.DSMRMeterListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DSMRBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author M. Volaart - Initial contribution
 */
@NonNullByDefault
public class DSMRBridgeHandler extends BaseBridgeHandler implements DSMRPortEventListener {

    private final Logger logger = LoggerFactory.getLogger(DSMRBridgeHandler.class);

    private final List<DSMRMeterListener> meterDiscoveryListeners = new ArrayList<>();

    /**
     * DSMRDevice that belongs to this DSMRBridgeHandler
     */
    @Nullable
    private DSMRDevice dsmrDevice;

    /**
     * Watchdog
     */
    @Nullable
    private ScheduledFuture<?> watchdog;

    private long receivedTimeout = System.nanoTime();

    /**
     * Timestamp of last P1 telegram received
     */
    private long telegramReceivedTime;

    /**
     * Constructor
     *
     * @param bridge the Bridge ThingType
     * @param discoveryService the DSMRMeterDiscoveryService to use for new DSMR meters
     */
    public DSMRBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    /**
     * The DSMRBridgeHandler does not support handling commands
     *
     * @param channelUID
     * @param command
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // DSMRBridgeHandler does not support commands
    }

    /**
     * Initializes this DSMRBridgeHandler
     *
     * This method will get the corresponding configuration and initialize and start the corresponding DSMRDevice
     */
    @Override
    public void initialize() {
        DSMRDeviceConfiguration deviceConfig = getConfigAs(DSMRDeviceConfiguration.class);

        logger.debug("Using configuration {}", deviceConfig);
        if (StringUtils.isBlank(deviceConfig.serialPort)) {
            logger.debug("portName is not configured, not starting device");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Serial Port name is not set");
        } else {
            logger.debug("Starting DSMR device");
            updateStatus(ThingStatus.UNKNOWN);
            receivedTimeout = TimeUnit.SECONDS.toNanos(deviceConfig.receivedTimeout);
            DSMRPortSettings fixedPortSettings = DSMRPortSettings.getPortSettingsFromConfiguration(deviceConfig);

            if (fixedPortSettings == null) {
                dsmrDevice = new DSMRAutoConfigDevice(deviceConfig.serialPort, this, scheduler);
            } else {
                dsmrDevice = new DSMRFixedConfigDevice(deviceConfig.serialPort, fixedPortSettings, this);
            }
            // Give the system some slack to start counting from now.
            resetLastReceivedState();
            dsmrDevice.start();
            watchdog = scheduler.scheduleWithFixedDelay(this::alive, receivedTimeout, receivedTimeout,
                    TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Adds a meter discovery listener
     *
     * @param meterDiscoveryListener the meter discovery listener to add
     *
     * @return true if listener is added, false otherwise
     */
    public boolean registerDSMRMeterListener(DSMRMeterListener meterDiscoveryListener) {
        logger.debug("Register DSMRMeterListener");
        return meterDiscoveryListeners.add(meterDiscoveryListener);
    }

    /**
     * Removes a meter discovery listener
     *
     * @param meterDiscoveryListener the meter discovery listener to remove
     *
     * @return true is listener is removed, false otherwise
     */
    public boolean unregisterDSMRMeterListener(DSMRMeterListener meterDiscoveryListener) {
        logger.debug("Unregister DSMRMeterListener");
        return meterDiscoveryListeners.remove(meterDiscoveryListener);
    }

    private void alive() {
        logger.trace("Bridge alive check with #{} children.", getThing().getThings().size());
        long deltaLastReceived = System.nanoTime() - telegramReceivedTime;

        if (deltaLastReceived > receivedTimeout) {
            logger.trace("No data received for {} seconds", TimeUnit.NANOSECONDS.toSeconds(deltaLastReceived));
            if (getThing().getStatus() == ThingStatus.ONLINE) {
                deviceOffline(ThingStatusDetail.COMMUNICATION_ERROR, "No data received for to long.");
            } else if (deltaLastReceived > DSMRDeviceConstants.RECOVERY_TIMEOUT_NANOS) {
                logger.debug("Restarting device after no data for {} seconds",
                        TimeUnit.NANOSECONDS.toSeconds(deltaLastReceived));
                resetLastReceivedState();
                dsmrDevice.restart();
            }
        }
    }

    private void resetLastReceivedState() {
        telegramReceivedTime = System.nanoTime();
        logger.trace("Telegram received time set: {}", telegramReceivedTime);
    }

    @Override
    public synchronized void handleTelegramReceived(List<CosemObject> cosemObjects, String telegramDetails) {
        if (cosemObjects.isEmpty()) {
            logger.debug("Parsing worked but something went wrong, so there were no CosemObjects:{}", telegramDetails);
            handleDSMRErrorEvent(ThingStatusDetail.COMMUNICATION_ERROR, telegramDetails);
        } else {
            resetLastReceivedState();
            meterValueReceived(new ArrayList<>(cosemObjects));
        }
    }

    @Override
    public void handlePortErrorEvent(DSMRPortEvent portEvent) {
        handleDSMRErrorEvent(ThingStatusDetail.CONFIGURATION_ERROR, portEvent.getEventDetails());
    }

    private synchronized void handleDSMRErrorEvent(ThingStatusDetail thingStatusDetail, String details) {
        // if (!switchBaudrateWhenStarting()) {
        resetLastReceivedState();
        // receivedCosemObjects.clear();
        deviceOffline(thingStatusDetail, details);
        // }
    }

    public void meterValueReceived(List<CosemObject> lastMeterValues) {
        updateStatus(ThingStatus.ONLINE);
        getThing().getThings().forEach(child -> {
            if (logger.isTraceEnabled()) {
                logger.trace("Update child:{} with {} objects", child.getThingTypeUID().getId(),
                        lastMeterValues.size());
            }
            if (child.getHandler() instanceof DSMRMeterHandler) {
                ((DSMRMeterHandler) child.getHandler()).meterValueReceived(lastMeterValues);
            }
        });
        meterDiscoveryListeners.forEach(m -> m.meterValueReceived(lastMeterValues));
    }

    /**
     * On dispose the DSMR device is removed
     */
    @Override
    public void dispose() {
        if (watchdog != null) {
            watchdog.cancel(true);
            watchdog = null;
        }
        // if (dsmrDevice != null) {
        // dsmrDevice.stopDevice();
        // dsmrDevice = null;
        // }
        if (dsmrDevice != null) {
            dsmrDevice.stop();
        }
    }

    public void deviceOffline(ThingStatusDetail thingStatusDetail, String details) {
        updateStatus(ThingStatus.OFFLINE, thingStatusDetail, details);
    }
}
