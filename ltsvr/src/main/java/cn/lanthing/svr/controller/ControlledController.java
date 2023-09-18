/*
 * BSD 3-Clause License
 *
 * Copyright (c) 2023 Zhennan Tu <zhennan.tu@gmail.com>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package cn.lanthing.svr.controller;

import cn.lanthing.codec.LtMessage;
import cn.lanthing.ltproto.LtProto;
import cn.lanthing.ltproto.server.*;
import cn.lanthing.ltsocket.ConnectionEvent;
import cn.lanthing.ltsocket.ConnectionEventType;
import cn.lanthing.ltsocket.MessageController;
import cn.lanthing.ltsocket.MessageMapping;
import cn.lanthing.svr.entity.OrderInfo;
import cn.lanthing.svr.service.*;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Slf4j
@MessageController
@Component
public class ControlledController {
    @Autowired
    private DeviceIDService deviceIDService;

    @Autowired
    private ControlledDeviceService controlledDeviceService;

    @Autowired
    private ControllingDeviceService controllingDeviceService;

    @Autowired
    private ControllingSocketService controllingSocketService;

    @Autowired
    private OrderService orderService;

    @ConnectionEvent(type = ConnectionEventType.Connected)
    public void onConnectionConnected(long connectionID) {
        controlledDeviceService.addSession(connectionID);
    }

    @ConnectionEvent(type = ConnectionEventType.Closed)
    public void onConnectionClosed(long connectionID) {
        Long deviceID = controlledDeviceService.removeSession(connectionID);
        if (deviceID != null) {
            orderService.controlledDeviceLogout(deviceID);
        }
    }

    @ConnectionEvent(type = ConnectionEventType.UnexpectedlyClosed)
    public void onConnectionUnexpectedlyClosed(long connectionID) {
        //
    }

    @MessageMapping(proto = LtProto.LoginDevice)
    public LtMessage handleLoginDevice(long connectionID, LoginDeviceProto.LoginDevice msg) {
        log.debug("Handling LoginDevice({}:{})", connectionID, msg.getDeviceId());
        var ack = LoginDeviceAckProto.LoginDeviceAck.newBuilder();
        if (!deviceIDService.isValidDeviceID(msg.getDeviceId())) {
            log.warn("LoginDevice failed: device id({}) not valid", msg.getDeviceId());
            ack.setErrCode(LoginDeviceAckProto.LoginDeviceAck.ErrCode.Failed);
            return new LtMessage(LtProto.LoginDeviceAck.ID, ack.build());
        }

        String sessionID = controlledDeviceService.loginDevice(connectionID, msg.getDeviceId(), msg.getAllowControl(), msg.getSessionId());
        if (Strings.isNullOrEmpty(sessionID)) {
            ack.setErrCode(LoginDeviceAckProto.LoginDeviceAck.ErrCode.Failed);
            log.info("LoginDevice failed({}:{})", connectionID, msg.getDeviceId());
        } else {
            ack.setErrCode(LoginDeviceAckProto.LoginDeviceAck.ErrCode.Success);
            ack.setSessionId(sessionID);
            log.info("LoginDevice success({}:{}:{})", connectionID, msg.getDeviceId(), sessionID);
        }
        return new LtMessage(LtProto.LoginDeviceAck.ID, ack.build());
    }

    @MessageMapping(proto = LtProto.OpenConnectionAck)
    public LtMessage handleOpenConnectionAck(long connectionID, OpenConnectionAckProto.OpenConnectionAck msg) {
        Long deviceID = controlledDeviceService.getDeviceIDByConnectionID(connectionID);
        if (deviceID == null) {
            log.error("Get device id by connection id failed!");
            return null;
        }
        OrderInfo orderInfo = orderService.getOrderByControlledDeviceID(deviceID);
        if (orderInfo == null) {
            log.error("Get order info by device id({}) failed", deviceID);
            return null;
        }
        Long controllingConnectionID = controllingDeviceService.getConnectionIDByDeviceID(orderInfo.fromDeviceID);
        if (controllingConnectionID == null) {
            log.warn("Get connection id by controlling device id({}) failed", orderInfo.fromDeviceID);
            return null;
        }
        var ack = RequestConnectionAckProto.RequestConnectionAck.newBuilder();
        if (msg.getErrCode() != OpenConnectionAckProto.OpenConnectionAck.ErrCode.Success) {
            ack.setErrCode(RequestConnectionAckProto.RequestConnectionAck.ErrCode.Failed)
                    .setRequestId(orderInfo.clientRequestID);
            boolean success = orderService.closeOrderFromControlled(orderInfo.roomID, orderInfo.toDeviceID);
            if (success) {
                log.info("Order with room id({}) closed", orderInfo.roomID);
            } else {
                log.warn("Order with room id({}) close failed", orderInfo.roomID);
            }
        } else {
            ack.setErrCode(RequestConnectionAckProto.RequestConnectionAck.ErrCode.Success)
                    .setRequestId(orderInfo.clientRequestID)
                    .setDeviceId(orderInfo.toDeviceID)
                    .setSignalingAddr(orderInfo.signalingAddress)
                    .setSignalingPort(orderInfo.signalingPort)
                    .setRoomId(orderInfo.roomID)
                    .setClientId(orderInfo.clientID)
                    .setAuthToken(orderInfo.authToken)
                    .setP2PUsername(orderInfo.p2pUsername)
                    .setP2PPassword(orderInfo.p2pPassword)
                    .setStreamingParams(msg.getStreamingParams());
            if (!CollectionUtils.isEmpty(orderInfo.reflexServers)) {
                ack.addAllReflexServers(orderInfo.reflexServers);
            }
        }
        controllingSocketService.send(controllingConnectionID, new LtMessage(LtProto.RequestConnectionAck.ID, ack.build()));
        return null;
    }

    @MessageMapping(proto = LtProto.CloseConnection)
    public LtMessage handleCloseConnection(long connectionID, CloseConnectionProto.CloseConnection msg) {
        Long deviceID = controlledDeviceService.getDeviceIDByConnectionID(connectionID);
        if (deviceID == null) {
            log.error("Get device id by connection id failed");
            return null;
        }
        boolean success = orderService.closeOrderFromControlled(msg.getRoomId(), deviceID);
        if (success) {
            log.info("Order with room id({}) closed", msg.getRoomId());
        } else {
            log.warn("Order with room id({}) close failed", msg.getRoomId());
        }
        return null;
    }
}
