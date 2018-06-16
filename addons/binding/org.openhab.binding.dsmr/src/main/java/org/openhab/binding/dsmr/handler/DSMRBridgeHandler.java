/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
import org.openhab.binding.dsmr.internal.device.DSMRDeviceThread;
import org.openhab.binding.dsmr.internal.device.DSMRFixedConfigDevice;
import org.openhab.binding.dsmr.internal.device.DSMRPortEventListener;
import org.openhab.binding.dsmr.internal.device.DSMRTcpDevice;
import org.openhab.binding.dsmr.internal.device.connector.DSMRPortErrorEvent;
import org.openhab.binding.dsmr.internal.device.connector.DSMRSerialSettings;
import org.openhab.binding.dsmr.internal.device.cosem.CosemObject;
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

    private static final int _3 = 10;

    private final Logger logger = LoggerFactory.getLogger(DSMRBridgeHandler.class);

    private final List<DSMRMeterListener> meterDiscoveryListeners = new ArrayList<>();

    /**
     * DSMRDevice that belongs to this DSMRBridgeHandler
     */
    @Nullable
    private DSMRDeviceThread dsmrDeviceThread;

    @Nullable
    private Thread thread;

    /**
     * Watchdog
     */
    @Nullable
    private ScheduledFuture<?> watchdog;

    private long receivedTimeoutNanos = System.nanoTime();

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

        logger.trace("Using configuration {}", deviceConfig);
        updateStatus(ThingStatus.UNKNOWN);
        receivedTimeoutNanos = TimeUnit.SECONDS.toNanos(deviceConfig.receivedTimeout);
        DSMRDevice dsmrDevice = createDevice(deviceConfig);
        // Give the system some slack to start counting from now.
        resetLastReceivedState();
        if (dsmrDevice == null) {
            logger.debug("portName is not configured, not starting device");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Serial Port name is not set");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "");
        } else {
            dsmrDeviceThread = new DSMRDeviceThread(dsmrDevice, this);
            thread = new Thread(dsmrDeviceThread);
            thread.start();
            watchdog = scheduler.scheduleWithFixedDelay(this::alive, receivedTimeoutNanos, receivedTimeoutNanos,
                    TimeUnit.NANOSECONDS);
        }
    }

    /**
     *
     * @param deviceConfig
     * @return
     */
    @Nullable
    private DSMRDevice createDevice(DSMRDeviceConfiguration deviceConfig) {
        DSMRDevice dsmrDevice;

        if (deviceConfig.isSerialAutoDetection()) {
            dsmrDevice = new DSMRAutoConfigDevice(deviceConfig.serialPort, this, scheduler,
                    deviceConfig.receivedTimeout);
        } else if (deviceConfig.isSerialFixedSettings()) {
            dsmrDevice = new DSMRFixedConfigDevice(deviceConfig.serialPort,
                    DSMRSerialSettings.getPortSettingsFromConfiguration(deviceConfig), this);
        } else if (deviceConfig.isTcpSettings()) {
            try {
                dsmrDevice = new DSMRTcpDevice(deviceConfig.ipAddress, deviceConfig.ipPort, scheduler, this);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            dsmrDevice = null;
        }
        return dsmrDevice;
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

    /**
     *
     */
    private void alive() {
        logger.trace("Bridge alive check with #{} children.", getThing().getThings().size());
        long deltaLastReceived = System.nanoTime() - telegramReceivedTime;

        if (deltaLastReceived > receivedTimeoutNanos) {
            logger.debug("No data received for {} seconds, restarting port if possible.",
                    TimeUnit.NANOSECONDS.toSeconds(deltaLastReceived));
            dsmrDeviceThread.restart();
            if (deltaLastReceived > receivedTimeoutNanos * _3) {
                logger.trace("Setting device offline if not yet done, and reset last received time.");
                if (getThing().getStatus() == ThingStatus.ONLINE) {
                    deviceOffline(ThingStatusDetail.COMMUNICATION_ERROR, "Not receiving data from meter.");
                }
                resetLastReceivedState();
            }
        }
    }

    /**
     *
     */
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
    public void handlePortErrorEvent(DSMRPortErrorEvent portEvent) {
        if (portEvent != DSMRPortErrorEvent.READ_ERROR) {
            handleDSMRErrorEvent(ThingStatusDetail.CONFIGURATION_ERROR, portEvent.getEventDetails());
        }
    }

    private synchronized void handleDSMRErrorEvent(ThingStatusDetail thingStatusDetail, String details) {
        // resetLastReceivedState();
        deviceOffline(thingStatusDetail, details);
    }

    /**
     *
     * @param lastMeterValues
     */
    private void meterValueReceived(List<CosemObject> lastMeterValues) {
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
        if (dsmrDeviceThread != null) {
            dsmrDeviceThread.stop();
        }
    }

    public void deviceOffline(ThingStatusDetail thingStatusDetail, String details) {
        updateStatus(ThingStatus.OFFLINE, thingStatusDetail, details);
    }
}
