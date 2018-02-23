/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.exec.spark.session;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.spark.HiveSparkClientFactory;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hive.spark.client.SparkClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SparkSessionManagerImpl implements SparkSessionManager {
    private static final Logger LOG = LoggerFactory.getLogger(SparkSessionManagerImpl.class);

    private final Map<String, SparkSession> createdSessions = new ConcurrentHashMap<>();
    private volatile boolean inited = false;

    private final Map<String, Integer> sessionRefTrack = new ConcurrentHashMap<>();

    private static SparkSessionManagerImpl instance;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    if (instance != null) {
                        instance.shutdown();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }

    public static synchronized SparkSessionManagerImpl getInstance()
            throws HiveException {
        if (instance == null) {
            instance = new SparkSessionManagerImpl();
        }

        return instance;
    }

    private SparkSessionManagerImpl() {

    }

    @Override
    public void setup(HiveConf hiveConf) throws HiveException {
        if (!inited) {
            synchronized (this) {
                if (!inited) {
                    LOG.info("Setting up the session manager.");
                    Map<String, String> conf = HiveSparkClientFactory.initiateSparkConf(hiveConf);
                    try {
                        SparkClientFactory.initialize(conf);
                        inited = true;
                    } catch (IOException e) {
                        throw new HiveException("Error initializing SparkClientFactory", e);
                    }
                }
            }
        }
    }

    private void incrRefTrack(String sessionId) {
        synchronized (sessionRefTrack) {
            Integer count;
            if (sessionRefTrack.containsKey(sessionId)) {
                count = sessionRefTrack.get(sessionId);
            } else {
                count = 0;
            }
            sessionRefTrack.put(sessionId, count + 1);
        }
    }

    private void decrRefTrack(String sessionId) {
        synchronized (sessionRefTrack) {
            Integer count = sessionRefTrack.get(sessionId) - 1;
            if (count == 0) {
                sessionRefTrack.remove(sessionId);
            } else {
                sessionRefTrack.put(sessionId, count);
            }
        }
    }

    /**
     * If the <i>existingSession</i> can be reused return it.
     * Otherwise
     *   - close it and remove it from the list.
     *   - create a new session and add it to the list.
     */
    @Override
    public SparkSession getSession(SparkSession existingSession, HiveConf conf, boolean doOpen)
            throws HiveException {
        setup(conf);

        String sessionId = conf.getVar(HiveConf.ConfVars.HIVESESSIONID);
        if (!sessionId.equals("")) {
            existingSession = createdSessions.get(sessionId);
        }

        if (existingSession != null) {
            incrRefTrack(existingSession.getSessionId());
            // Open the session if it is closed.
            if (!existingSession.isOpen() && doOpen) {
                existingSession.open(conf);
            }
            return existingSession;
        }

        SparkSession sparkSession;
        if (!sessionId.equals("")) {
            sparkSession = new SparkSessionImpl(sessionId);
        } else {
            sparkSession = new SparkSessionImpl();
        }

        if (doOpen) {
            sparkSession.open(conf);
        }

        incrRefTrack(sparkSession.getSessionId());

        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("New session (%s) is created.", sparkSession.getSessionId()));
        }
        createdSessions.put(sparkSession.getSessionId(), sparkSession);
        return sparkSession;
    }

    @Override
    public void returnSession(SparkSession sparkSession) throws HiveException {
        if (sparkSession == null) {
            return;
        }
        decrRefTrack(sparkSession.getSessionId());
    }

    @Override
    public void closeSession(SparkSession sparkSession) throws HiveException {
        // Use reference track to manage session. Only when no one is using the
        // session, it's allowed to be closed
        String sessionId = sparkSession.getSessionId();
        if (!sessionRefTrack.containsKey(sessionId)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Closing session (%s).", sessionId));
            }
            synchronized (createdSessions) {
                createdSessions.get(sessionId).close();
                createdSessions.remove(sessionId);
            }
        }
        // do nothing to a running session
    }

    @Override
    public void shutdown() {
        LOG.info("Closing the session manager.");
        synchronized (createdSessions) {
            Iterator<SparkSession> it = createdSessions.values().iterator();
            while (it.hasNext()) {
                SparkSession session = it.next();
                session.close();
            }
            createdSessions.clear();
        }
        sessionRefTrack.clear();
        inited = false;
        SparkClientFactory.stop();
    }
}
