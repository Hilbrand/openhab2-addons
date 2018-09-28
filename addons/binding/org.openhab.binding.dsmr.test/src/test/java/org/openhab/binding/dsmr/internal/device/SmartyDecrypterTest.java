/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.dsmr.internal.device;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.Test;
import org.openhab.binding.dsmr.internal.TelegramReaderUtil;
import org.openhab.binding.dsmr.internal.device.p1telegram.P1Telegram;
import org.openhab.binding.dsmr.internal.device.p1telegram.P1TelegramListener;
import org.openhab.binding.dsmr.internal.device.p1telegram.P1TelegramParser;

/**
 * Test class for the {@link SmartyDecrypter}.
 *
 * @author Hilbrand Bouwkamp - Initial contribution
 */
@NonNullByDefault
public class SmartyDecrypterTest {

    private static final String KEY = "D491470F47126332B07D1923B3504188";
    private static final int[] TELEGRAM = new int[] { 0xDB, 0x08, 0x53, 0x41, 0x47, 0x67, 0x70, 0x01, 0xBD, 0x54, 0x82,
            0x02, 0x7A, 0x30, 0x00, 0x05, 0xA8, 0xE3, 0x80, 0x6E, 0xE6, 0xE6, 0x39, 0x27, 0x4C, 0x7B, 0xC5, 0x70, 0x95,
            0xF8, 0x72, 0xB0, 0x8D, 0xDE, 0x62, 0x1F, 0xB7, 0x4E, 0xE8, 0x1E, 0x5E, 0xBE, 0x34, 0x2C, 0x93, 0xD8, 0xE7,
            0x37, 0x81, 0xFB, 0x2A, 0x1E, 0xB8, 0x71, 0x00, 0x74, 0xA5, 0x4F, 0xC5, 0x7A, 0xA7, 0xD1, 0xD9, 0x92, 0x36,
            0xC4, 0x2E, 0x2E, 0xC0, 0x1A, 0x03, 0x24, 0xEF, 0xC7, 0xF0, 0x2E, 0x3B, 0xA2, 0xFA, 0x43, 0x19, 0x6C, 0xA6,
            0x03, 0x83, 0x8A, 0xB8, 0x32, 0x2D, 0xFF, 0xA8, 0x3F, 0x7E, 0x83, 0x93, 0x2E, 0x60, 0x07, 0x40, 0x6B, 0x11,
            0x2B, 0x41, 0x74, 0x5F, 0x38, 0x80, 0x56, 0x46, 0xD5, 0x3F, 0xEA, 0x7A, 0x02, 0x9F, 0xA7, 0x9C, 0x36, 0xD9,
            0xD7, 0x77, 0xDA, 0x8B, 0x5A, 0x03, 0xED, 0x3E, 0xC6, 0xE4, 0x85, 0xDE, 0xC0, 0xC9, 0x8D, 0x7D, 0x53, 0xC0,
            0xBD, 0x17, 0x5B, 0x22, 0x8E, 0x01, 0xF4, 0x66, 0xD9, 0x84, 0x37, 0x77, 0x80, 0x85, 0x03, 0xD6, 0x30, 0x15,
            0x8C, 0x82, 0x36, 0xD8, 0x68, 0x08, 0x8E, 0xB1, 0x39, 0x77, 0xBD, 0xFC, 0x47, 0x43, 0x67, 0xE5, 0x57, 0x13,
            0x6F, 0xEB, 0x6D, 0x53, 0x7F, 0x18, 0x30, 0x44, 0x84, 0xA0, 0x14, 0xB4, 0x90, 0x99, 0xA6, 0x3C, 0x18, 0xD4,
            0x30, 0x3A, 0x4C, 0x53, 0x85, 0x3A, 0x0C, 0xA8, 0xC5, 0xB0, 0xE3, 0xE8, 0x05, 0xBF, 0xD8, 0x42, 0x54, 0x10,
            0xFA, 0xB5, 0x22, 0xB3, 0x5A, 0x8F, 0x25, 0x8B, 0x57, 0xBB, 0x1E, 0x97, 0xE2, 0x48, 0x9B, 0x6F, 0x37, 0x07,
            0x17, 0x53, 0xAF, 0xFB, 0xB4, 0xEE, 0xBC, 0xA5, 0x02, 0xEE, 0xA5, 0x92, 0xB8, 0x80, 0x85, 0xAE, 0x15, 0x5B,
            0xCE, 0xA8, 0xF7, 0x32, 0xD6, 0xB0, 0x11, 0x5B, 0xB9, 0xCE, 0xD7, 0x6B, 0xE5, 0xB8, 0x93, 0xB2, 0xA7, 0xEF,
            0xC8, 0x16, 0x3C, 0x17, 0x1E, 0x77, 0xC3, 0xD2, 0x9A, 0x72, 0xB7, 0x47, 0x8E, 0xD6, 0xDF, 0xE2, 0xC4, 0x8B,
            0xF3, 0xF9, 0xD8, 0xF8, 0x95, 0xCA, 0x1C, 0xB4, 0x2E, 0xAA, 0xC4, 0xCB, 0x21, 0xD6, 0xEA, 0xB5, 0x1E, 0x77,
            0xE4, 0xD6, 0x02, 0x9D, 0x78, 0x84, 0x27, 0xDD, 0x5B, 0xFC, 0x46, 0xBD, 0xD3, 0xE8, 0xA3, 0x2D, 0xBB, 0x6F,
            0x93, 0xB4, 0x84, 0x2B, 0x07, 0x3E, 0x9B, 0x6F, 0xE6, 0xE5, 0xDF, 0xC0, 0x58, 0xB5, 0xF4, 0x54, 0xAA, 0x3E,
            0xF1, 0x62, 0xBD, 0xF9, 0x3C, 0xB1, 0xE0, 0xC4, 0x52, 0xDB, 0xB2, 0xBE, 0x4C, 0xB4, 0xC6, 0x7D, 0x16, 0x9E,
            0x2A, 0x30, 0x61, 0x8F, 0xA2, 0x16, 0x54, 0x41, 0xB6, 0xD3, 0xC2, 0x2F, 0x1C, 0x36, 0x27, 0xE3, 0x5F, 0xFF,
            0xCF, 0x9F, 0x19, 0x47, 0xD7, 0xA6, 0xAE, 0x94, 0x2F, 0xC2, 0x1E, 0x24, 0x6F, 0x0E, 0xFC, 0x45, 0x6A, 0x78,
            0x89, 0xC9, 0x61, 0xC9, 0x3E, 0xA0, 0x89, 0xEE, 0xF6, 0xD1, 0xA4, 0x40, 0x56, 0xBA, 0xAA, 0xB3, 0x52, 0xCB,
            0xEA, 0x7D, 0x7E, 0x20, 0x67, 0x57, 0xAF, 0x0D, 0x42, 0x70, 0x64, 0xB0, 0x58, 0xD5, 0x72, 0x35, 0xA7, 0x8F,
            0x8D, 0xB9, 0x1C, 0xE4, 0xD6, 0x0A, 0x3C, 0x6B, 0xAB, 0xD9, 0x9A, 0x61, 0x16, 0x9B, 0x17, 0x2F, 0x24, 0x14,
            0xF5, 0x65, 0xBD, 0xE0, 0x23, 0x96, 0x54, 0xEA, 0xC7, 0x75, 0x52, 0xFB, 0xCC, 0x2A, 0x2F, 0xA1, 0x02, 0xD7,
            0xCD, 0x00, 0x3D, 0xDB, 0xEB, 0x9C, 0x1F, 0x81, 0x1D, 0xBC, 0x69, 0x53, 0x76, 0x33, 0x6E, 0x2E, 0x9D, 0x6C,
            0xFC, 0x73, 0xFC, 0xB4, 0xA2, 0x94, 0x46, 0xBD, 0x7C, 0xE3, 0x17, 0x0E, 0x58, 0x56, 0x76, 0x6B, 0x25, 0xE8,
            0x20, 0x49, 0xEE, 0x55, 0x08, 0xEC, 0x18, 0x16, 0x68, 0x5F, 0x1D, 0xCE, 0xC2, 0x38, 0x19, 0x12, 0x16, 0xDF,
            0xFB, 0xA0, 0x64, 0x7D, 0x32, 0xA1, 0x55, 0x9D, 0x99, 0x64, 0x0A, 0x15, 0xF2, 0x4A, 0xB0, 0x21, 0x72, 0xDB,
            0x3D, 0x56, 0x44, 0x54, 0xE7, 0xA0, 0x34, 0x6C, 0x09, 0x93, 0x99, 0xB8, 0x77, 0x0A, 0x9B, 0xDA, 0xA6, 0x19,
            0xC7, 0xC5, 0x23, 0x05, 0x26, 0xD6, 0xB8, 0x04, 0x99, 0xA0, 0xEE, 0x0C, 0xD4, 0xFC, 0x46, 0xA6, 0x77, 0xAF,
            0x68, 0xEF, 0x4D, 0xEE, 0x9E, 0x76, 0xB7, 0x8A, 0xC8, 0xA0, 0x4F, 0x43, 0x1D, 0xB3, 0x98, 0x6F, 0xCF, 0xD4,
            0x5E, 0xE1, 0xF1, 0x07, 0x99, 0xAF, 0x0B, 0x79, 0x4A, 0x41, 0x03, 0x73, 0x5D, 0x6D, 0xDA, 0x38, 0x29, 0x17,
            0x3F, 0x80, 0xF1, 0x38, 0xA5, 0x30, 0x1A, 0xFD, 0xF1, 0xDB, 0x87, 0x74, 0xF0, 0xC4, 0x0E, 0x6E, 0x71, 0x26,
            0x4B, 0x33, 0x4A, 0x94, 0x3F, 0xC1, 0x05, 0x52, 0xB0, 0x75, 0x2F, 0x3A, 0x0D, 0x7F, 0xD8, 0x04, 0x58, 0x59,
            0x54, 0x08, 0xF2, 0x85, 0x0F, 0x17, };

    /**
     * Tests decrypting of a single complete Smarty telegram.
     */
    @Test
    public void testSmartyDecrypter() {
        AtomicReference<String> telegramResult = new AtomicReference<String>("");
        P1TelegramListener telegramListener = new P1TelegramListener() {
            @Override
            public void telegramReceived(P1Telegram telegram) {
                telegramResult.set(telegram.getRawTelegram());
            }
        };
        SmartyDecrypter decoder = new SmartyDecrypter(new P1TelegramParser(telegramListener),
                new DSMRTelegramListener(KEY), KEY);
        decoder.setLenientMode(true);
        byte[] data = new byte[TELEGRAM.length];
        for (int i = 0; i < TELEGRAM.length; i++) {
            data[i] = (byte) TELEGRAM[i];
        }

        decoder.parse(data, data.length);
        String expected = new String(TelegramReaderUtil.readRawTelegram("smarty"));

        assertEquals("", expected, telegramResult.get());
    }
}
