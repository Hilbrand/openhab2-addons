/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.internal.device;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.openhab.binding.dsmr.DSMRBindingConstants;
import org.openhab.binding.dsmr.internal.device.DSMRDeviceConstants.DSMRDeviceEvent;
import org.openhab.binding.dsmr.internal.device.DSMRDeviceConstants.DSMRPortEvent;
import org.openhab.binding.dsmr.internal.device.DSMRDeviceConstants.DeviceState;
import org.openhab.binding.dsmr.internal.device.cosem.CosemObject;
import org.openhab.binding.dsmr.internal.discovery.DSMRMeterDetector;
import org.openhab.binding.dsmr.internal.discovery.DSMRMeterDiscoveryListener;
import org.openhab.binding.dsmr.internal.meter.DSMRMeter;
import org.openhab.binding.dsmr.internal.meter.DSMRMeterDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DSMRDevice class represents the physical Thing within OpenHAB2 context
 *
 * The DSMRDevice will start in the state INITIALIZING where the serial port is opened.
 *
 * The device is waiting in the state STARTING till valid P1 telegrams are received. Then the device will enter the
 * state ONLINE.
 *
 * During the STARTING state the class will switch baud rate automatically in case of communication problems.
 *
 * If there are communication problems or no P1 telegrams are received (anymore), the device will enter the state
 * OFFLINE.
 *
 * If the OpenHAB2 system wants the binding to shutdown, the DSMRDevice enters the SHUTDOWN state and will close
 * and release the OS resources.
 *
 * In case of configuration errors the DSMRDevice will enter the state CONFIGURATION_PROBLEM
 *
 * @author M. Volaart
 * @since 2.1.0
 */
public class DSMRDevice implements DSMRPortEventListener {
    private final Logger logger = LoggerFactory.getLogger(DSMRDevice.class);

    /**
     * DSMR Device configuration
     */
    private DSMRDeviceConfiguration deviceConfiguration;

    /**
     * Device status class
     */
    private static class DeviceStatus {
        /** State of the DSMR Device */
        private DeviceState deviceState;

        /** Timestamp when the device entered the deviceState */
        private long tsEnteredDeviceState;

        /** Timestamp of last P1 telegram received */
        private long lastTelegramReceivedTS;

        /** CosemObject from received P1 telegram */
        private List<CosemObject> receivedCosemObjects;
    }

    /**
     * Current device status
     */
    private final DeviceStatus deviceStatus;

    /**
     * DSMR Port instance
     */
    private DSMRPort dsmrPort;

    /**
     * List of available logical meters (DSMRMeter)
     */
    private List<DSMRMeter> availableMeters;

    /**
     * listener for discoveries of new DSMRMeters
     */
    private DSMRMeterDiscoveryListener discoveryListener;

    /**
     * listener for state changes of this DSMR Device
     */
    private DSMRDeviceStateListener deviceStateListener;

    /**
     * Executor for delayed tasks
     */
    private ExecutorService executor;

    /**
     * DSMRMeterDetector instance for discovering new meters
     */
    private DSMRMeterDetector meterDetector;

    /**
     * Creates a new DSMRDevice.
     *
     * @param deviceConfiguration {@link DSMRDeviceConfiguration} containing the configuration of this instance of the
     *            DSMR Device
     * @param deviceStateListener {@link DSMRDeviceStateListener} listener that will be notified in case of state
     *            changes of this DSMR device
     * @param discoveryListener {@link DSMRMeterDiscoveryListener} listener that will be notified in case a new
     *            logic DSMR meter is detected
     * @param availableMeters list of {@link DSMRMeter} objects that handle P1 Telegrams
     */
    public DSMRDevice(DSMRDeviceConfiguration deviceConfiguration, DSMRDeviceStateListener deviceStateListener,
            DSMRMeterDiscoveryListener discoveryListener, List<DSMRMeter> availableMeters) {
        this.deviceConfiguration = deviceConfiguration;
        this.discoveryListener = discoveryListener;
        this.deviceStateListener = deviceStateListener;
        this.availableMeters = availableMeters;

        meterDetector = new DSMRMeterDetector();
        deviceStatus = new DeviceStatus();
        deviceStatus.receivedCosemObjects = new ArrayList<>();
    }

    private void setNewDeviceState(DeviceState newDeviceState, String eventDetails) {
        synchronized (deviceStatus) {
            DeviceState currentDeviceState = deviceStatus.deviceState;

            // Update device status
            deviceStatus.deviceState = newDeviceState;
            deviceStatus.tsEnteredDeviceState = System.currentTimeMillis();

            // Notify listeners
            if (deviceStateListener != null) {
                deviceStateListener.stateUpdated(currentDeviceState, newDeviceState, eventDetails);
                if (currentDeviceState != newDeviceState) {
                    deviceStateListener.stateChanged(currentDeviceState, newDeviceState, eventDetails);
                }
            } else {
                logger.error("No device state listener available, binding will not work properly!");
            }

        }
    }

    /**
     * Handles DSMR Device events
     *
     * If the device has the state SHUTDOWN or CONFIGURATION_PROBLEM no events will be handled.
     *
     * This method will notify the device state listener about the state update (always) / change (only if new
     * state differs from the old state)
     * The eventDetails argument will be used as state details for the state update / state change
     *
     * @param event {@link DSMRDeviceEvent} to handle
     * @param eventDetails the details about the event
     */
    private void handleDSMRDeviceEvent(DSMRDeviceEvent event, String eventDetails) {
        synchronized (deviceStatus) {
            DeviceState currentDeviceState = deviceStatus.deviceState;

            logger.debug("Handle DSMRDeviceEvent {} in state {}", event, currentDeviceState);

            if (currentDeviceState != DeviceState.SHUTDOWN && currentDeviceState != DeviceState.CONFIGURATION_PROBLEM) {
                switch (event) {
                    case CONFIGURATION_ERROR:
                        setNewDeviceState(DeviceState.CONFIGURATION_PROBLEM, eventDetails);
                        break;
                    case ERROR:
                        // General error occurred
                        setNewDeviceState(DeviceState.OFFLINE, eventDetails);
                        break;
                    case DSMR_PORT_OPENED:
                        if (currentDeviceState == DeviceState.INITIALIZING
                                || currentDeviceState == DeviceState.SWITCH_PORT_SPEED) {
                            setNewDeviceState(DeviceState.STARTING, eventDetails);
                        } else {
                            logger.debug("Ignoring event {} in state {}", event, currentDeviceState);
                        }
                        break;
                    case DSMR_PORT_CLOSED:
                        /*
                         * Handle DSMR_PORT_CLOSED is expected during INITIALIZE and SWITCH_PORT_SPEED, so no specific
                         * handling needed
                         */
                        if (currentDeviceState != DeviceState.SWITCH_PORT_SPEED
                                && currentDeviceState != DeviceState.INITIALIZING) {
                            // Port is closed, set DeviceState offline
                            setNewDeviceState(DeviceState.OFFLINE, eventDetails);
                        }
                        break;
                    case READ_ERROR:
                        if (currentDeviceState == DeviceState.ONLINE) {
                            setNewDeviceState(DeviceState.OFFLINE, eventDetails);
                        } else if (currentDeviceState == DeviceState.STARTING) {
                            setNewDeviceState(DeviceState.SWITCH_PORT_SPEED, eventDetails);
                        } else if (currentDeviceState != DeviceState.OFFLINE) {
                            logger.debug("Ignore event {} in state {}", event, currentDeviceState);
                        }
                        break;
                    case SHUTDOWN:
                        setNewDeviceState(DeviceState.SHUTDOWN, eventDetails);
                        break;
                    case INITIALIZE:
                        setNewDeviceState(DeviceState.INITIALIZING, eventDetails);
                        break;
                    case TELEGRAM_RECEIVED:
                        if (currentDeviceState == DeviceState.OFFLINE || currentDeviceState == DeviceState.STARTING) {
                            dsmrPort.setLenientMode(deviceConfiguration.lenientMode);
                            setNewDeviceState(DeviceState.ONLINE, eventDetails);
                        } else if (currentDeviceState != DeviceState.ONLINE) {
                            logger.debug("Cannot make transition from {} to ONLINE", currentDeviceState);
                        }
                        break;
                    case SWITCH_BAUDRATE:
                        if (currentDeviceState == DeviceState.ONLINE) {
                            // Switch baudrate is not expected when valid telegrams were received
                            setNewDeviceState(DeviceState.OFFLINE, eventDetails);
                        } else if (currentDeviceState == DeviceState.STARTING) {
                            setNewDeviceState(DeviceState.SWITCH_PORT_SPEED, eventDetails);
                        } else {
                            logger.debug("Ignore event {} in state {}", event, currentDeviceState);
                        }
                        break;
                    default:
                        logger.warn("Unknown event {}", event);
                }
                // Handle the current state asynchronous
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        handleDeviceState();
                    }
                });
            } else {
                logger.debug("Event won't be processed due to state {}", deviceStatus.deviceState);
            }
        }
    }

    /**
     * Handle the current device state
     */
    private void handleDeviceState() {
        synchronized (deviceStatus) {
            logger.debug("Current DSMRDevice state {}", deviceStatus.deviceState);

            switch (deviceStatus.deviceState) {
                case CONFIGURATION_PROBLEM:
                    /*
                     * Device has a configuration problem. Do nothing, user must change the configuration.
                     * This will trigger an event in the OH2 framework that will finally lead to reinitializing the
                     * DSMRDevice with a new configuration
                     */
                    break;
                case INITIALIZING:
                    handleInitializeDSMRDevice();
                    break;
                case OFFLINE:
                    if (System.currentTimeMillis()
                            - deviceStatus.tsEnteredDeviceState > DSMRDeviceConstants.RECOVERY_TIMEOUT) {
                        /* Device is still in OFFLINE, try to recover by entering INITIALIZING */
                        logger.info("In offline mode for at least {} ms, reinitialize device",
                                DSMRDeviceConstants.RECOVERY_TIMEOUT);

                        handleDSMRDeviceEvent(DSMRDeviceEvent.INITIALIZE, "In offline mode for too long, recovering");
                    }

                    break;
                case ONLINE:
                    if (!deviceStatus.receivedCosemObjects.isEmpty()) {
                        List<CosemObject> cosemObjects = new ArrayList<>();
                        cosemObjects.addAll(deviceStatus.receivedCosemObjects);

                        // Handle cosem objects asynchronous
                        executor.submit(new Runnable() {
                            @Override
                            public void run() {
                                logger.debug("Processing {} cosem objects", cosemObjects);
                                sendCosemObjects(cosemObjects);
                            }
                        });

                        deviceStatus.receivedCosemObjects.clear();
                    } else if (System.currentTimeMillis()
                            - deviceStatus.lastTelegramReceivedTS > DSMRDeviceConstants.RECOVERY_TIMEOUT) {
                        logger.info("No messages received for at least {} ms, reinitialize device",
                                DSMRDeviceConstants.RECOVERY_TIMEOUT);
                        /* Device did not receive telegrams for too long, to to recover by entering INITIALIZING */
                        handleDSMRDeviceEvent(DSMRDeviceEvent.ERROR, "No telegrams received for too long");
                    } else {
                        logger.debug("No Cosem objects to handle");
                    }
                    break;
                case SHUTDOWN:
                    /* Shutdown device */
                    handleShutdownDSMRDevice();
                    break;
                case STARTING:
                    if (System.currentTimeMillis()
                            - deviceStatus.tsEnteredDeviceState > DSMRDeviceConstants.SERIAL_PORT_AUTO_DETECT_TIMEOUT) {
                        /* Device did not receive telegrams for too long, switch port speed */
                        handleDSMRDeviceEvent(DSMRDeviceEvent.SWITCH_BAUDRATE,
                                DSMRDeviceEvent.SWITCH_BAUDRATE.getEventDetails());
                    }
                    break;
                case SWITCH_PORT_SPEED:
                    // Close current port
                    dsmrPort.close();

                    // Switch port settings first (otherwise initialize could use current port speed settings)
                    dsmrPort.switchPortSpeed();

                    // Open port again
                    dsmrPort.open();
                    break;
                default:
                    logger.warn("Unknown state {}", deviceStatus.deviceState);

                    break;
            }
            if (!deviceStatus.receivedCosemObjects.isEmpty()) {
                logger.debug("Dropping {} Cosem objects due to state {}", deviceStatus.receivedCosemObjects.size(),
                        deviceStatus.deviceState);
            }
            deviceStatus.receivedCosemObjects.clear();
        }
    }

    /**
     * Send cosemObjects to the available meters
     * This method will iterate through available meters and notify them about
     * the CosemObjects received.
     *
     * @param cosemObjects. The list of {@link CosemObjects} received.
     */
    private void sendCosemObjects(List<CosemObject> cosemObjects) {
        // Iterate through available meters and let them process the cosemObjects
        for (DSMRMeter meter : availableMeters) {
            logger.debug("Processing CosemObjects for meter {}", meter);
            List<CosemObject> processedCosemObjects = meter.handleCosemObjects(cosemObjects);
            logger.debug("Processed cosemObjects {}", processedCosemObjects);
            cosemObjects.removeAll(processedCosemObjects);
        }

        /*
         * There are still unhandled cosemObjects. Start discovery of DSMR meters
         */
        if (!cosemObjects.isEmpty()) {
            logger.info("There are unhandled CosemObjects, start autodetecting meters");

            List<DSMRMeterDescriptor> detectedMeters = meterDetector.detectMeters(cosemObjects);
            logger.info("Detected the following new meters: {}", detectedMeters.toString());

            if (discoveryListener != null) {
                for (DSMRMeterDescriptor meterDescriptor : detectedMeters) {
                    boolean success = discoveryListener.meterDiscovered(meterDescriptor);
                    if (!success) {
                        logger.info("DiscoveryListener {} rejected meter descriptor {}", discoveryListener,
                                meterDescriptor);
                    }
                }
            } else {
                logger.warn("There is no listener for new meters!");
            }
        }
    }

    /**
     * Initialize the DSMR Device
     */
    private void handleInitializeDSMRDevice() {
        if (dsmrPort != null) {
            dsmrPort.close();
            dsmrPort = null;
        }

        // Start parser in lenient mode to prevent flooding logs during initialization
        dsmrPort = new DSMRPort(deviceConfiguration.serialPort, this,
                DSMRPortSettings.getPortSettingsFromDeviceConfiguration(deviceConfiguration), true);

        // Open the DSMR Port
        dsmrPort.open();
    }

    /**
     * Handle the shutdown of the DSMR device
     */
    private void handleShutdownDSMRDevice() {
        if (dsmrPort != null) {
            dsmrPort.close();
            dsmrPort = null;
        }
    }

    /**
     * Starts the DSMR device
     */
    public void startDevice() {
        executor = ThreadPoolManager.getPool(DSMRBindingConstants.DSMR_THREAD_POOL_NAME);

        logger.debug("Starting device and entering INITIALIZING state");
        handleDSMRDeviceEvent(DSMRDeviceEvent.INITIALIZE, DSMRDeviceEvent.INITIALIZE.getEventDetails());
    }

    /**
     * Stops the DSMR device
     */
    public void stopDevice() {
        handleDSMRDeviceEvent(DSMRDeviceEvent.SHUTDOWN, DSMRDeviceEvent.SHUTDOWN.getEventDetails());
    }

    /**
     * Callback for handling port events
     *
     * @param portEvent {@link DSMRPortEvent} to handle
     */
    @Override
    public void handleDSMRPortEvent(DSMRPortEvent portEvent) {
        logger.debug("Handle port event {}", portEvent);

        switch (portEvent) {
            case CLOSED:
                handleDSMRDeviceEvent(DSMRDeviceEvent.DSMR_PORT_CLOSED, portEvent.getEventDetails());
                break;
            case CONFIGURATION_ERROR:
                handleDSMRDeviceEvent(DSMRDeviceEvent.CONFIGURATION_ERROR, portEvent.getEventDetails());
                break;
            case DONT_EXISTS:
                handleDSMRDeviceEvent(DSMRDeviceEvent.CONFIGURATION_ERROR, portEvent.getEventDetails());
                break;
            case ERROR:
                handleDSMRDeviceEvent(DSMRDeviceEvent.ERROR, portEvent.getEventDetails());
                break;
            case LINE_BROKEN:
                handleDSMRDeviceEvent(DSMRDeviceEvent.READ_ERROR, portEvent.getEventDetails());
                break;
            case IN_USE:
                handleDSMRDeviceEvent(DSMRDeviceEvent.ERROR, portEvent.getEventDetails());
                break;
            case NOT_COMPATIBLE:
                handleDSMRDeviceEvent(DSMRDeviceEvent.ERROR, portEvent.getEventDetails());
                break;
            case OPENED:
                handleDSMRDeviceEvent(DSMRDeviceEvent.DSMR_PORT_OPENED, portEvent.getEventDetails());
                break;
            case WRONG_BAUDRATE:
                handleDSMRDeviceEvent(DSMRDeviceEvent.SWITCH_BAUDRATE, portEvent.getEventDetails());
                break;
            case READ_ERROR:
                handleDSMRDeviceEvent(DSMRDeviceEvent.READ_ERROR, portEvent.getEventDetails());
                break;
            case READ_OK:
                /* Ignore this event */
                break;
            default:
                logger.warn("Unknown DSMR Port event");
                handleDSMRDeviceEvent(DSMRDeviceEvent.ERROR, "Unknown port event");
                break;
        }
    }

    /**
     * Callback for the watchdog
     *
     * If the binding and the real device is working correctly this callback return immediately
     *
     * Otherwise it will trigger the {@link #handleDeviceState()} state to evaluate the current state
     */
    public void alive() {
        logger.debug("Alive");
        if (System.currentTimeMillis() - deviceStatus.tsEnteredDeviceState > DSMRDeviceConstants.RECOVERY_TIMEOUT
                && System.currentTimeMillis()
                        - deviceStatus.lastTelegramReceivedTS > DSMRDeviceConstants.RECOVERY_TIMEOUT) {
            logger.debug("Calling handle device state");
            /*
             * No update of state of received telegrams for a period of RECOVERY_TIMEOUT
             * Evaluate current state
             */
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    handleDeviceState();
                }
            });
        }
    }

    @Override
    public void p1TelegramReceived(List<CosemObject> cosemObjects, String telegramDetails) {
        if (!cosemObjects.isEmpty()) {
            // Telegram received, update timestamp
            deviceStatus.lastTelegramReceivedTS = System.currentTimeMillis();
            deviceStatus.receivedCosemObjects.addAll(cosemObjects);
        } else {
            logger.debug("Parsing was succesful, however there were no CosemObjects");
        }
        handleDSMRDeviceEvent(DSMRDeviceEvent.TELEGRAM_RECEIVED, telegramDetails);
    }
}
