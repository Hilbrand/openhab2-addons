/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.internal.discovery;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.dsmr.internal.device.cosem.CosemObject;

/**
 * This interface is notified of new meter discoveries
 *
 * @author M. Volaart - Initial contribution
 */
@NonNullByDefault
interface DSMRBridgeDiscoveryListener {
    /**
     * A new bridge is discovered
     *
     * @param serialPort serial port identifier (e.g. /dev/ttyUSB0 or COM1)
     * @param cosemObjects List of cosem objects that triggered the discovery. These can be used to detect meters.
     * @return true if the new bridge is accepted, false otherwise
     */
    public boolean bridgeDiscovered(String serialPort, List<CosemObject> cosemObjects);
}
