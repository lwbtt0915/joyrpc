package io.joyrpc.health;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.joyrpc.Plugin.DOCTOR;

/**
 * 监控状态探针
 */
public class HealthProbe {

    protected static volatile HealthProbe INSTANCE;

    /**
     * 健康状态
     */
    protected volatile HealthState state = HealthState.HEALTHY;

    /**
     * 构造函数
     */
    protected HealthProbe() {
        /**
         * 启动一个线程定期检查
         */
        Thread thread = new Thread(() -> {
            CountDownLatch lath = new CountDownLatch(1);
            while (true) {
                try {
                    lath.await(5000, TimeUnit.MILLISECONDS);
                    state = diagnose();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "doctor");
        thread.setDaemon(true);
        thread.start();
    }

    public HealthState getState() {
        return state;
    }

    /**
     * 获取单例
     *
     * @return
     */
    public static final HealthProbe getInstance() {
        if (INSTANCE == null) {
            synchronized (HealthProbe.class) {
                if (INSTANCE == null) {
                    INSTANCE = new HealthProbe();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 诊断健康状态
     *
     * @return
     */
    protected HealthState diagnose() {
        HealthState result = HealthState.HEALTHY;
        HealthState state;
        for (Doctor doctor : DOCTOR.extensions()) {
            state = doctor.diagnose();
            if (state.ordinal() > result.ordinal()) {
                result = state;
            }
            if (state == HealthState.DEAD) {
                break;
            }
        }
        return result;
    }

}