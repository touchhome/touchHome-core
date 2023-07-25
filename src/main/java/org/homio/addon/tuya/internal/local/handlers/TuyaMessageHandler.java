package org.homio.addon.tuya.internal.local.handlers;

import static com.sshtools.common.util.Utils.bytesToHex;
import static org.homio.addon.tuya.internal.local.CommandType.DP_QUERY;
import static org.homio.addon.tuya.internal.local.CommandType.DP_QUERY_NOT_SUPPORTED;
import static org.homio.addon.tuya.internal.local.CommandType.SESS_KEY_NEG_FINISH;
import static org.homio.addon.tuya.internal.local.CommandType.SESS_KEY_NEG_RESPONSE;
import static org.homio.addon.tuya.internal.local.CommandType.STATUS;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.homio.addon.tuya.internal.local.DeviceStatusListener;
import org.homio.addon.tuya.internal.local.MessageWrapper;
import org.homio.addon.tuya.internal.local.TuyaDeviceCommunicator;
import org.homio.addon.tuya.internal.local.dto.TcpStatusPayload;
import org.homio.addon.tuya.internal.util.CryptoUtil;

/**
 * The {@link TuyaMessageHandler} is a Netty channel handler
 */
@Log4j2
@RequiredArgsConstructor
public class TuyaMessageHandler extends ChannelDuplexHandler {
    private final String deviceId;
    private final TuyaDeviceCommunicator.KeyStore keyStore;
    private final DeviceStatusListener deviceStatusListener;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("{}{}: Connection established.", deviceId,
                Objects.requireNonNullElse(ctx.channel().remoteAddress(), ""));
        deviceStatusListener.connectionStatus(true);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("{}{}: Connection terminated.", deviceId,
                Objects.requireNonNullElse(ctx.channel().remoteAddress(), ""));
        deviceStatusListener.connectionStatus(false);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof MessageWrapper<?> m) {
            if (m.commandType == DP_QUERY || m.commandType == STATUS) {
                Map<Integer, Object> stateMap = null;
                if (m.content instanceof TcpStatusPayload) {
                    TcpStatusPayload payload = (TcpStatusPayload) Objects.requireNonNull(m.content);
                    stateMap = payload.protocol == 4 ? payload.data.dps : payload.dps;
                }

                if (stateMap != null && !stateMap.isEmpty()) {
                    deviceStatusListener.processDeviceStatus(stateMap);
                }
            } else if (m.commandType == DP_QUERY_NOT_SUPPORTED) {
                deviceStatusListener.processDeviceStatus(Map.of());
            } else if (m.commandType == SESS_KEY_NEG_RESPONSE) {
                byte[] localKeyHmac = CryptoUtil.hmac(keyStore.getRandom(), keyStore.getDeviceKey());
                byte[] localKeyExpectedHmac = Arrays.copyOfRange((byte[]) m.content, 16, 16 + 32);

                if (!Arrays.equals(localKeyHmac, localKeyExpectedHmac)) {
                    log.warn(
                            "{}{}: Session key negotiation failed during Hmac validation: calculated {}, expected {}",
                            deviceId, Objects.requireNonNullElse(ctx.channel().remoteAddress(), ""),
                            localKeyHmac != null ? bytesToHex(localKeyHmac) : "<null>",
                            bytesToHex(localKeyExpectedHmac));
                    return;
                }

                byte[] remoteKey = Arrays.copyOf((byte[]) m.content, 16);
                byte[] remoteKeyHmac = CryptoUtil.hmac(remoteKey, keyStore.getDeviceKey());
                MessageWrapper<?> response = new MessageWrapper<>(SESS_KEY_NEG_FINISH, remoteKeyHmac);

                ctx.channel().writeAndFlush(response);

                byte[] sessionKey = CryptoUtil.generateSessionKey(keyStore.getRandom(), remoteKey,
                        keyStore.getDeviceKey());
                if (sessionKey == null) {
                    log.warn("{}{}: Session key negotiation failed because session key is null.", deviceId,
                            Objects.requireNonNullElse(ctx.channel().remoteAddress(), ""));
                    return;
                }
                keyStore.setSessionKey(sessionKey);
            }
        }
    }
}
