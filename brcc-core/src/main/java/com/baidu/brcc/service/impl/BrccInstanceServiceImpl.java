/*
 * Copyright (c) Baidu Inc. All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.brcc.service.impl;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baidu.brcc.common.InstanceEventType;
import com.baidu.brcc.common.ThreadPoolUtils;
import com.baidu.brcc.dao.BrccInstanceMapper;
import com.baidu.brcc.dao.base.BaseMapper;
import com.baidu.brcc.domain.BrccInstance;
import com.baidu.brcc.domain.BrccInstanceExample;
import com.baidu.brcc.domain.meta.MetaBrccInstance;
import com.baidu.brcc.dto.InstanceInfoEventDto;
import com.baidu.brcc.service.BrccInstanceService;
import com.baidu.brcc.service.base.GenericServiceImpl;

@Service("brccInstanceService")
public class BrccInstanceServiceImpl extends GenericServiceImpl<BrccInstance, Long, BrccInstanceExample>
        implements BrccInstanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrccInstanceServiceImpl.class);

    @Autowired
    private BrccInstanceMapper brccInstanceMapper;

    @Override
    public BaseMapper<BrccInstance, Long, BrccInstanceExample> getMapper() {
        return brccInstanceMapper;
    }

    @Override
    public BrccInstanceExample newExample() {
        return BrccInstanceExample.newBuilder().build();
    }

    @Override
    public BrccInstanceExample newIdInExample(List<Long> ids) {
        return BrccInstanceExample.newBuilder().build().createCriteria().andIdIn(ids).toExample();
    }

    @Override
    public void submitEvent(InstanceInfoEventDto event) {
        ThreadPoolUtils.submitTask(() -> {
            // TODO save to DB
            LOGGER.info("event", event);
            if (event == null) {
                return;
            }
            String versionId = event.getVersionId();
            String clientIp = event.getClientIp();

            // clientIp, versionId为空 跳过此次心跳上报
            if (StringUtils.isBlank(clientIp) || StringUtils.isBlank(versionId)) {
                return;
            }
            Long vid = null;
            try {
                vid = Long.parseLong(versionId);
            } catch (Exception ex) {
                return;
            }
            BrccInstance instanceInDb = selectOneByExample(BrccInstanceExample.newBuilder()
                            .build()
                            .createCriteria()
                            .andVersionIdEqualTo(vid)
                            .andIpEqualTo(clientIp)
                            .toExample(),
                    MetaBrccInstance.COLUMN_NAME_ID,
                    MetaBrccInstance.COLUMN_NAME_CURRENTCHECKSUM,
                    MetaBrccInstance.COLUMN_NAME_CURRENTCHECKSUMTIME
            );
            try {
                InstanceEventType eventType = event.getEventType();
                if (eventType == InstanceEventType.Heartbeat) {
                    if (instanceInDb == null) {
                        return;
                    }

                    String checkSum = event.getCheckSum();
                    if (!StringUtils.equals(checkSum, instanceInDb.getCurrentChecksum())) {
                        instanceInDb.setLastChecksum(instanceInDb.getCurrentChecksum());
                        instanceInDb.setLastChecksumTime(instanceInDb.getLastChecksumTime());
                        instanceInDb.setCurrentChecksum(checkSum);
                        instanceInDb.setCurrentChecksumTime(new Date());
                    }
                    String netCost = event.getNetCost();
                    if (StringUtils.isNotBlank(netCost)) {
                        instanceInDb.setNetCost(Integer.parseInt(netCost));
                    }
                    instanceInDb.setHeartbeatTime(new Date());
                    updateByPrimaryKeySelective(instanceInDb);
                } else {
                    BrccInstance instance = event.toBrccInstance();
                    if (instanceInDb == null) {
                        // 新增
                        instance.setCreateTime(new Date());
                        insertSelective(instance);
                    } else {
                        // 更新
                        String checkSum = event.getCheckSum();
                        instance.setId(instanceInDb.getId());
                        if (!StringUtils.equals(checkSum, instanceInDb.getCurrentChecksum())) {
                            instance.setLastChecksum(instanceInDb.getCurrentChecksum());
                            instance.setLastChecksumTime(instanceInDb.getLastChecksumTime());
                        }
                        updateByPrimaryKeySelective(instance);
                    }
                }
            } catch (Throwable ex) {
                LOGGER.error("submitEvent error", ex);
            }

        });
    }
}
