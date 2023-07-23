/**
 * Copyright (c) 2021-2023 Contributors to the SmartHome/J project
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
package org.homio.addon.tuya.internal.local.dto;

import java.util.Map;



/**
 * The {@link TcpStatusPayload} encapsulates the payload of a TCP status message
 *
 * @author Jan N. Klug - Initial contribution
 */

public class TcpStatusPayload {
    public int protocol = -1;
    public String devId = "";
    public String gwId = "";
    public String uid = "";
    public long t = 0;
    public Map<Integer, Object> dps = Map.of();
    public Data data = new Data();

    @Override
    public String toString() {
        return "TcpStatusPayload{protocol=" + protocol + ", devId='" + devId + "', gwId='" + gwId + "', uid='" + uid
                + "', t=" + t + ", dps=" + dps + ", data=" + data + "}";
    }

    public static class Data {
        public Map<Integer, Object> dps = Map.of();

        @Override
        public String toString() {
            return "Data{dps=" + dps + "}";
        }
    }
}
