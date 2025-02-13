// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/mysql/MysqlProtoTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.mysql;

import com.starrocks.catalog.Database;
import com.starrocks.common.DdlException;
import com.starrocks.mysql.privilege.Auth;
import com.starrocks.mysql.privilege.PrivPredicate;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.UserIdentity;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MysqlProtoTest {
    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(MysqlProtoTest.class);
    @Mocked
    private MysqlChannel channel;
    @Mocked
    private MysqlPassword password;
    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private Auth auth;

    @Before
    public void setUp() throws DdlException {

        // mock auth
        new Expectations() {
            {
                auth.checkGlobalPriv((ConnectContext) any, (PrivPredicate) any);
                minTimes = 0;
                result = true;

                auth.checkPassword(anyString, anyString, (byte[]) any, (byte[]) any, (List<UserIdentity>) any);
                minTimes = 0;
                result = new Delegate() {
                    boolean fakeCheckPassword(String remoteUser, String remoteHost, byte[] remotePasswd,
                                              byte[] randomString,
                                              List<UserIdentity> currentUser) {
                        UserIdentity userIdentity = new UserIdentity("defaut_cluster:user", "192.168.1.1");
                        currentUser.add(userIdentity);
                        return true;
                    }
                };

                globalStateMgr.getDb(anyString);
                minTimes = 0;
                result = new Database();

                globalStateMgr.getAuth();
                minTimes = 0;
                result = auth;

                globalStateMgr.changeCatalogDb((ConnectContext) any, anyString);
                minTimes = 0;
            }
        };

        new Expectations(globalStateMgr) {
            {
                GlobalStateMgr.getCurrentState();
                minTimes = 0;
                result = globalStateMgr;

                GlobalStateMgr.getCurrentState();
                minTimes = 0;
                result = globalStateMgr;

                globalStateMgr.isUsingNewPrivilege();
                minTimes = 0;
                result = false;
            }
        };

    }

    private void mockChannel(String user, boolean sendOk) throws Exception {
        // mock channel
        new Expectations() {
            {
                channel.sendAndFlush((ByteBuffer) any);
                minTimes = 0;
                result = new Delegate() {
                    void sendAndFlush(ByteBuffer packet) throws IOException {
                        if (!sendOk) {
                            throw new IOException();
                        }
                    }
                };
            }
        };

        // mock auth packet
        MysqlSerializer serializer = MysqlSerializer.newInstance();

        // capability
        serializer.writeInt4(MysqlCapability.DEFAULT_CAPABILITY.getFlags());
        // max packet size
        serializer.writeInt4(1024000);
        // character set
        serializer.writeInt1(33);
        // reserved
        serializer.writeBytes(new byte[23]);
        // user name
        serializer.writeNulTerminateString(user);
        // plugin data
        serializer.writeInt1(20);
        byte[] buf = new byte[20];
        for (int i = 0; i < 20; ++i) {
            buf[i] = (byte) ('a' + i);
        }
        serializer.writeBytes(buf);
        // database
        serializer.writeNulTerminateString("database");

        ByteBuffer buffer = serializer.toByteBuffer();
        new Expectations() {
            {
                channel.fetchOnePacket();
                minTimes = 0;
                result = buffer;

                channel.getRemoteIp();
                minTimes = 0;
                result = "192.168.1.1";
            }
        };
    }

    private void mockPassword(boolean res) {
        // mock password
        new Expectations(password) {
            {
                MysqlPassword.checkScramble((byte[]) any, (byte[]) any, (byte[]) any);
                minTimes = 0;
                result = res;

                MysqlPassword.createRandomString(20);
                minTimes = 0;
                result = new byte[20];

                MysqlPassword.getSaltFromPassword((byte[]) any);
                minTimes = 0;
                result = new byte[20];
            }
        };
    }

    private ByteBuffer mockChangeUserPacket(String user) {
        // mock change user packet
        MysqlSerializer serializer = MysqlSerializer.newInstance();
        // code
        serializer.writeInt1(17);
        // user name
        serializer.writeNulTerminateString(user);
        // plugin data
        serializer.writeInt1(20);
        byte[] buf = new byte[20];
        for (int i = 0; i < 20; ++i) {
            buf[i] = (byte) ('a' + i);
        }
        serializer.writeBytes(buf);
        // database
        serializer.writeNulTerminateString("database");
        // character set
        serializer.writeInt2(33);

        return serializer.toByteBuffer();
    }

    private void mockAccess() throws Exception {
    }

    @Test
    public void testNegotiate() throws Exception {
        mockChannel("user", true);
        mockPassword(true);
        mockAccess();
        ConnectContext context = new ConnectContext(null);
        context.setGlobalStateMgr(globalStateMgr);
        context.setThreadLocalInfo();
        Assert.assertTrue(MysqlProto.negotiate(context));
    }

    @Test
    public void testChangeUser() throws Exception {
        mockChannel("user", true);
        mockPassword(true);
        mockAccess();
        ConnectContext context = new ConnectContext(null);
        context.setGlobalStateMgr(globalStateMgr);
        context.setThreadLocalInfo();
        Assert.assertTrue(MysqlProto.negotiate(context));
        ByteBuffer changeUserPacket = mockChangeUserPacket("change-user");
        Assert.assertTrue(MysqlProto.changeUser(context, changeUserPacket));
    }

    @Test(expected = IOException.class)
    public void testNegotiateSendFail() throws Exception {
        mockChannel("user", false);
        mockPassword(true);
        mockAccess();
        ConnectContext context = new ConnectContext(null);
        MysqlProto.negotiate(context);
        Assert.fail("No Exception throws.");
    }

    @Test
    public void testNegotiateInvalidPasswd() throws Exception {
        mockChannel("user", true);
        mockPassword(false);
        mockAccess();
        ConnectContext context = new ConnectContext(null);
        context.setGlobalStateMgr(globalStateMgr);
        Assert.assertTrue(MysqlProto.negotiate(context));
    }

    @Test
    public void testNegotiateNoUser() throws Exception {
        mockChannel("", true);
        mockPassword(true);
        mockAccess();
        ConnectContext context = new ConnectContext(null);
        Assert.assertFalse(MysqlProto.negotiate(context));
    }

    @Test
    public void testRead() {
        MysqlSerializer serializer = MysqlSerializer.newInstance();
        serializer.writeInt1(200);
        serializer.writeInt2(65535);
        serializer.writeInt3(65537);
        serializer.writeInt4(123456789);
        serializer.writeInt6(1234567896);
        serializer.writeInt8(1234567898);
        serializer.writeVInt(1111123452);
        // string
        serializer.writeBytes("hello".getBytes(StandardCharsets.UTF_8));
        serializer.writeLenEncodedString("world");
        serializer.writeNulTerminateString("i have dream");
        serializer.writeEofString("you have dream too");

        ByteBuffer buffer = serializer.toByteBuffer();
        Assert.assertEquals(200, MysqlProto.readInt1(buffer));
        Assert.assertEquals(65535, MysqlProto.readInt2(buffer));
        Assert.assertEquals(65537, MysqlProto.readInt3(buffer));
        Assert.assertEquals(123456789, MysqlProto.readInt4(buffer));
        Assert.assertEquals(1234567896, MysqlProto.readInt6(buffer));
        Assert.assertEquals(1234567898, MysqlProto.readInt8(buffer));
        Assert.assertEquals(1111123452, MysqlProto.readVInt(buffer));

        Assert.assertEquals("hello", new String(MysqlProto.readFixedString(buffer, 5)));
        Assert.assertEquals("world", new String(MysqlProto.readLenEncodedString(buffer)));
        Assert.assertEquals("i have dream", new String(MysqlProto.readNulTerminateString(buffer)));
        Assert.assertEquals("you have dream too", new String(MysqlProto.readEofString(buffer)));
    }

}
