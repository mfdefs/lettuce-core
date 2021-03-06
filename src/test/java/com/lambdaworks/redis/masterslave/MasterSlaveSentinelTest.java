/*
 * Copyright 2011-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lambdaworks.redis.masterslave;

import static com.lambdaworks.redis.TestSettings.port;
import static com.lambdaworks.redis.masterslave.MasterSlaveTest.slaveCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.lambdaworks.TestClientResources;
import com.lambdaworks.redis.*;
import com.lambdaworks.redis.codec.Utf8StringCodec;
import com.lambdaworks.redis.sentinel.AbstractSentinelTest;
import com.lambdaworks.redis.sentinel.SentinelRule;

import io.netty.channel.group.ChannelGroup;

/**
 * @author Mark Paluch
 */
public class MasterSlaveSentinelTest extends AbstractSentinelTest {

    static {
        sentinelClient = RedisClient.create(TestClientResources.get(),
                RedisURI.Builder.sentinel(TestSettings.host(), MASTER_ID).build());
    }

    @Rule
    public SentinelRule sentinelRule = new SentinelRule(sentinelClient, false, 26379, 26380);

    private RedisURI sentinelUri = RedisURI.Builder.sentinel(TestSettings.host(), 26379, MASTER_ID).build();
    private Pattern pattern = Pattern.compile("role:(\\w+)");

    @Before
    public void before() throws Exception {
        sentinelRule.needMasterWithSlave(MASTER_ID, port(3), port(4));
    }

    @Test
    public void testMasterSlaveSentinelBasic() throws Exception {

        RedisURI uri = RedisURI.create(
                "redis-sentinel://127.0.0.1:21379,127.0.0.1:22379,127.0.0.1:26379?sentinelMasterId=mymaster&timeout=5s");
        StatefulRedisMasterSlaveConnection<String, String> connection = MasterSlave.connect(sentinelClient,
                new Utf8StringCodec(), uri);

        connection.setReadFrom(ReadFrom.MASTER);
        String server = slaveCall(connection);
        assertThatServerIs(server, "master");

        connection.close();
    }

    @Test
    public void masterSlaveConnectionShouldSetClientName() throws Exception {

        RedisURI redisURI = RedisURI.Builder.sentinel(TestSettings.host(), MASTER_ID).withClientName("my-client").build();

        StatefulRedisMasterSlaveConnection<String, String> connection = MasterSlave.connect(sentinelClient,
                new Utf8StringCodec(), redisURI);

        assertThat(connection.sync().clientGetname()).isEqualTo(redisURI.getClientName());
        connection.sync().quit();
        assertThat(connection.sync().clientGetname()).isEqualTo(redisURI.getClientName());

        connection.close();
    }

    @Test
    public void testMasterSlaveSentinelWithTwoUnavailableSentinels() throws Exception {

        RedisURI uri = RedisURI.create(
                "redis-sentinel://127.0.0.1:21379,127.0.0.1:22379,127.0.0.1:26379?sentinelMasterId=mymaster&timeout=5s");
        StatefulRedisMasterSlaveConnection<String, String> connection = MasterSlave.connect(sentinelClient,
                new Utf8StringCodec(), uri);

        connection.setReadFrom(ReadFrom.MASTER);
        String server = connection.sync().info("replication");
        assertThatServerIs(server, "master");

        connection.close();
    }

    @Test
    public void testMasterSlaveSentinelWithUnavailableSentinels() throws Exception {

        RedisURI uri = RedisURI.create("redis-sentinel://127.0.0.1:21379,127.0.0.1:21379?sentinelMasterId=mymaster&timeout=5s");

        try {
            MasterSlave.connect(sentinelClient, new Utf8StringCodec(), uri);
            fail("Missing RedisConnectionException");
        } catch (RedisConnectionException e) {
            assertThat(e.getCause()).hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    public void testMasterSlaveSentinelConnectionCount() throws Exception {

        ChannelGroup channels = (ChannelGroup) ReflectionTestUtils.getField(sentinelClient, "channels");
        int count = channels.size();

        StatefulRedisMasterSlaveConnection<String, String> connection = MasterSlave.connect(sentinelClient,
                new Utf8StringCodec(), sentinelUri);

        connection.sync().ping();
        connection.setReadFrom(ReadFrom.SLAVE);
        slaveCall(connection);

        assertThat(channels.size()).isEqualTo(count + 2 /* connections */ + 1 /* sentinel connections */);

        connection.close();
    }

    @Test
    public void testMasterSlaveSentinelClosesSentinelConnections() throws Exception {

        ChannelGroup channels = (ChannelGroup) ReflectionTestUtils.getField(sentinelClient, "channels");
        int count = channels.size();

        StatefulRedisMasterSlaveConnection<String, String> connection = MasterSlave.connect(sentinelClient,
                new Utf8StringCodec(), sentinelUri);

        connection.sync().ping();
        connection.setReadFrom(ReadFrom.SLAVE);
        slaveCall(connection);
        connection.close();

        assertThat(channels.size()).isEqualTo(count);
    }

    protected void assertThatServerIs(String server, String expectation) {
        Matcher matcher = pattern.matcher(server);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(expectation);
    }
}
