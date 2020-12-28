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
package org.openhab.binding.enphase.internal.handler;

import static org.openhab.binding.enphase.internal.EnphaseBindingConstants.*;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.cache.ExpiringCache;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerService;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.enphase.internal.EnphaseBindingConstants;
import org.openhab.binding.enphase.internal.EnvoyConfiguration;
import org.openhab.binding.enphase.internal.EnvoyConnectionException;
import org.openhab.binding.enphase.internal.EnvoyHostAddressCache;
import org.openhab.binding.enphase.internal.EnvoyNoHostnameException;
import org.openhab.binding.enphase.internal.discovery.EnphaseDevicesDiscoveryService;
import org.openhab.binding.enphase.internal.dto.EnvoyEnergyDTO;
import org.openhab.binding.enphase.internal.dto.InventoryJsonDTO.DeviceDTO;
import org.openhab.binding.enphase.internal.dto.InverterDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BridgeHandler for the Envoy gateway.
 *
 * @author Thomas Hentschel - Initial contribution
 * @author Hilbrand Bouwkamp - Initial contribution
 */
@NonNullByDefault
public class EnvoyBridgeHandler extends BaseBridgeHandler {

    private enum FeatureStatus {
        UNKNOWN,
        SUPPORTED,
        UNSUPPORTED
    }

    private static final long RETRY_RECONNECT_SECONDS = 10;

    private final Logger logger = LoggerFactory.getLogger(EnvoyBridgeHandler.class);
    private final EnvoyConnector connector;
    private final EnvoyHostAddressCache envoyHostnameCache;

    private @NonNullByDefault({}) EnvoyConfiguration configuration;
    private @Nullable ScheduledFuture<?> updataDataFuture;
    private @Nullable ScheduledFuture<?> updateHostnameFuture;
    private @Nullable ExpiringCache<Map<String, @Nullable InverterDTO>> invertersCache;
    private @Nullable ExpiringCache<Map<String, @Nullable DeviceDTO>> devicesCache;
    private @Nullable EnvoyEnergyDTO productionDTO;
    private @Nullable EnvoyEnergyDTO consumptionDTO;
    private FeatureStatus consumptionSupported = FeatureStatus.UNKNOWN;
    private FeatureStatus inventoryJsonSupported = FeatureStatus.UNKNOWN;

    public EnvoyBridgeHandler(final Bridge thing, final HttpClient httpClient,
            final EnvoyHostAddressCache envoyHostAddressCache) {
        super(thing);
        connector = new EnvoyConnector(httpClient);
        this.envoyHostnameCache = envoyHostAddressCache;
    }

    @Override
    public void handleCommand(final ChannelUID channelUID, final Command command) {
        if (command instanceof RefreshType) {
            refresh(channelUID);
        }
    }

    private void refresh(final ChannelUID channelUID) {
        final EnvoyEnergyDTO data = ENVOY_CHANNELGROUP_CONSUMPTION.equals(channelUID.getGroupId()) ? consumptionDTO
                : productionDTO;

        if (data == null) {
            updateState(channelUID, UnDefType.UNDEF);
        } else {
            switch (channelUID.getIdWithoutGroup()) {
                case ENVOY_WATT_HOURS_TODAY:
                    updateState(channelUID, new QuantityType<>(data.wattHoursToday, SmartHomeUnits.WATT_HOUR));
                    break;
                case ENVOY_WATT_HOURS_SEVEN_DAYS:
                    updateState(channelUID, new QuantityType<>(data.wattHoursSevenDays, SmartHomeUnits.WATT_HOUR));
                    break;
                case ENVOY_WATT_HOURS_LIFETIME:
                    updateState(channelUID, new QuantityType<>(data.wattHoursLifetime, SmartHomeUnits.WATT_HOUR));
                    break;
                case ENVOY_WATTS_NOW:
                    updateState(channelUID, new QuantityType<>(data.wattsNow, SmartHomeUnits.WATT));
                    break;
            }
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(EnphaseDevicesDiscoveryService.class);
    }

    @Override
    public void initialize() {
        configuration = getConfigAs(EnvoyConfiguration.class);
        if (!EnphaseBindingConstants.isValidSerial(configuration.serialnumber)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Serial Number is not valid");
        }
        if (configuration.hostname.isEmpty()) {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Waiting to retrieve ip address of the envoy gateway. Can take up to a minute.");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
        connector.setConfiguration(configuration);
        consumptionSupported = FeatureStatus.UNKNOWN;
        inventoryJsonSupported = FeatureStatus.UNKNOWN;
        invertersCache = new ExpiringCache<>(Duration.of(configuration.refresh, ChronoUnit.MINUTES),
                this::refreshInverters);
        devicesCache = new ExpiringCache<>(Duration.of(configuration.refresh, ChronoUnit.MINUTES),
                this::refreshDevices);
        updataDataFuture = scheduler.scheduleWithFixedDelay(this::updateData, 0, configuration.refresh,
                TimeUnit.MINUTES);
    }

    /**
     * Method called by the ExpiringCache when no inverter data is present to get the data from the Envoy gateway.
     * When there are connection problems it will start a scheduled job to try to reconnect to the
     *
     * @return the inverter data from the Envoy gateway or null if no data is available.
     */
    private @Nullable Map<String, @Nullable InverterDTO> refreshInverters() {
        try {
            return connector.getInverters().stream()
                    .collect(Collectors.toMap(InverterDTO::getSerialNumber, Function.identity()));
        } catch (final EnvoyNoHostnameException e) {
            // ignore hostname exception here. It's already handled by others.
        } catch (final EnvoyConnectionException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
        return null;
    }

    private @Nullable Map<String, @Nullable DeviceDTO> refreshDevices() {
        try {
            if (inventoryJsonSupported != FeatureStatus.UNSUPPORTED) {
                final Map<String, @Nullable DeviceDTO> devicesData = connector.getInventoryJson().stream()
                        .flatMap(inv -> Stream.of(inv.devices).map(d -> {
                            d.setType(inv.type);
                            return d;
                        })).collect(Collectors.toMap(DeviceDTO::getSerialNumber, Function.identity()));

                inventoryJsonSupported = FeatureStatus.SUPPORTED;
                return devicesData;
            }
        } catch (final EnvoyNoHostnameException e) {
            // ignore hostname exception here. It's already handled by others.
        } catch (final EnvoyConnectionException e) {
            if (inventoryJsonSupported == FeatureStatus.UNKNOWN) {
                logger.info(
                        "This Ephase Envoy device ({}) doesn't seem to support inventory json data. So no inventory json channels are set.",
                        getThing().getUID());
                inventoryJsonSupported = FeatureStatus.UNSUPPORTED;
            } else if (consumptionSupported == FeatureStatus.SUPPORTED) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Returns the data for the inverters. It get the data from cache or updates the cache if possible in case no data
     * is available.
     *
     * @param force force a cache refresh
     * @return data if present or null
     */
    public @Nullable Map<String, @Nullable InverterDTO> getInvertersData(final boolean force) {
        final ExpiringCache<Map<String, @Nullable InverterDTO>> invertersCache = this.invertersCache;

        if (invertersCache == null || !isOnline()) {
            return null;
        } else {
            if (force) {
                invertersCache.invalidateValue();
            }
            return invertersCache.getValue();
        }
    }

    /**
     * Returns the data for the devices. It get the data from cache or updates the cache if possible in case no data
     * is available.
     *
     * @param force force a cache refresh
     * @return data if present or null
     */
    public @Nullable Map<String, @Nullable DeviceDTO> getDevices(final boolean force) {
        final ExpiringCache<Map<String, @Nullable DeviceDTO>> devicesCache = this.devicesCache;

        if (devicesCache == null || !isOnline()) {
            return null;
        } else {
            if (force) {
                devicesCache.invalidateValue();
            }
            return devicesCache.getValue();
        }
    }

    /**
     * Method called by the refresh thread.
     */
    public synchronized void updateData() {
        try {
            updateEnvoy();
            updateInverters();
            updateDevices();
        } catch (final EnvoyNoHostnameException e) {
            scheduleHostnameUpdate(false);
        } catch (final RuntimeException e) {
            logger.debug("Unexpected error in Enphase {}: ", getThing().getUID(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private void updateEnvoy() throws EnvoyNoHostnameException {
        try {
            productionDTO = connector.getProduction();
            setConsumptionDTOData();
            getThing().getChannels().stream().map(Channel::getUID).filter(this::isLinked).forEach(this::refresh);
            if (isInitialized() && (getThing().getStatus() != ThingStatus.ONLINE
                    || getThing().getStatusInfo().getStatusDetail() != ThingStatusDetail.NONE)) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (final EnvoyConnectionException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            scheduleHostnameUpdate(false);
        }
    }

    /**
     * Retrieve consumption data if supported, and keep track if this feature is supported by the device.
     *
     * @throws EnvoyConnectionException
     */
    private void setConsumptionDTOData() throws EnvoyConnectionException {
        if (consumptionSupported != FeatureStatus.UNSUPPORTED && isOnline()) {
            try {
                consumptionDTO = connector.getConsumption();
                consumptionSupported = FeatureStatus.SUPPORTED;
            } catch (final EnvoyNoHostnameException e) {
                //
            } catch (final EnvoyConnectionException e) {
                if (consumptionSupported == FeatureStatus.UNKNOWN) {
                    logger.info(
                            "This Enphsae Envoy device ({}) doesn't seem to support consumption data. So no consumption channels are set.",
                            getThing().getUID());
                    consumptionSupported = FeatureStatus.UNSUPPORTED;
                } else if (consumptionSupported == FeatureStatus.SUPPORTED) {
                    throw e;
                }
            }
        }
    }

    /**
     * Updates channels of the inverter things with inverter specific data.
     */
    private void updateInverters() {
        final Map<String, @Nullable InverterDTO> inverters = getInvertersData(false);

        if (inverters != null) {
            getThing().getThings().stream().map(Thing::getHandler).filter(h -> h instanceof EnphaseInverterHandler)
                    .map(EnphaseInverterHandler.class::cast)
                    .forEach(invHandler -> updateInverter(inverters, invHandler));
        }
    }

    private void updateInverter(final @Nullable Map<String, @Nullable InverterDTO> inverters,
            final EnphaseInverterHandler invHandler) {
        if (inverters == null) {
            return;
        }
        final InverterDTO inverterDTO = inverters.get(invHandler.getSerialNumber());

        invHandler.refreshInverterChannels(inverterDTO);
        if (inventoryJsonSupported == FeatureStatus.UNSUPPORTED) {
            // if inventory json is supported device status is set in #updateDevices
            invHandler.refreshDeviceStatus(inverterDTO == null ? ERROR_NODATA : DEVICE_STATUS_OK);
        }
    }

    /**
     * Updates channels of the device things with device specific data.
     * This data is not available on all envoy devices.
     */
    private void updateDevices() {
        final Map<String, @Nullable DeviceDTO> devices = getDevices(false);

        if (devices != null) {
            getThing().getThings().stream().map(Thing::getHandler).filter(h -> h instanceof EnphaseDeviceHandler)
                    .map(EnphaseDeviceHandler.class::cast)
                    .forEach(invHandler -> invHandler.refreshDeviceState(devices.get(invHandler.getSerialNumber())));
        }
    }

    /**
     * Schedules a hostname update, but only schedules the task when not yet running or forced.
     * Force is used to reschedule the task and should only be used from within {@link #updateHostname()}.
     *
     * @param force if true will always schedule the task
     */
    private synchronized void scheduleHostnameUpdate(final boolean force) {
        if (force || updateHostnameFuture == null) {
            logger.debug("Schedule hostname/ip address update for thing {} in {} seconds.", getThing().getUID(),
                    RETRY_RECONNECT_SECONDS);
            updateHostnameFuture = scheduler.schedule(this::updateHostname, RETRY_RECONNECT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void childHandlerInitialized(final ThingHandler childHandler, final Thing childThing) {
        if (childHandler instanceof EnphaseInverterHandler) {
            updateInverter(getInvertersData(false), (EnphaseInverterHandler) childHandler);
        }
        if (childHandler instanceof EnphaseDeviceHandler) {
            final Map<String, @Nullable DeviceDTO> devices = getDevices(false);

            if (devices != null) {
                ((EnphaseDeviceHandler) childHandler)
                        .refreshDeviceState(devices.get(((EnphaseDeviceHandler) childHandler).getSerialNumber()));
            }
        }
    }

    /**
     * Handles a host name / ip address update.
     */
    private void updateHostname() {
        final String lastKnownHostname = envoyHostnameCache.getLastKnownHostAddress(configuration.serialnumber);

        if (lastKnownHostname.isEmpty()) {
            scheduleHostnameUpdate(true);
        } else {
            final Configuration config = editConfiguration();

            config.put(CONFIG_HOSTNAME, lastKnownHostname);
            logger.info("Enphase Envoy ({}) hostname/ip address set to {}", getThing().getUID(), lastKnownHostname);
            configuration.hostname = lastKnownHostname;
            connector.setConfiguration(configuration);
            updateConfiguration(config);
            updateData();
            // The task is done so the future can be released by setting it to null.
            updateHostnameFuture = null;
        }
    }

    @Override
    public void dispose() {
        final ScheduledFuture<?> retryFuture = this.updateHostnameFuture;
        if (retryFuture != null) {
            retryFuture.cancel(true);
        }
        final ScheduledFuture<?> inverterFuture = this.updataDataFuture;

        if (inverterFuture != null) {
            inverterFuture.cancel(true);
        }
    }

    private boolean isOnline() {
        return getThing().getStatus() == ThingStatus.ONLINE
                && getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.NONE;
    }

    @Override
    public String toString() {
        return "EnvoyBridgeHandler(" + thing.getUID() + ") Status: " + thing.getStatus();
    }
}
