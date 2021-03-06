package com.alibaba.rocketmq.common;

/**
 * 定义各个版本信息
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 */
public class MQVersion {
    // TODO 每次发布版本都要修改此处版本号
    public static final int CurrentVersion = Version.V3_0_0_SNAPSHOT.ordinal();

    enum Version {
        V3_0_0_SNAPSHOT,
        V3_0_0,
        V3_0_1_SNAPSHOT,
        V3_0_1,
        V3_0_2_SNAPSHOT,
        V3_0_2,
        V3_0_3_SNAPSHOT,
        V3_0_3,
        V3_0_4_SNAPSHOT,
        V3_0_4,
    }


    public static String getVersionDesc(int value) {
        Version v = Version.values()[value];
        return v.name();
    }


    public static Version value2Version(int value) {
        return Version.values()[value];
    }
}
