package com.alibaba.tesla.appmanager.domain.req.deployconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployConfigListReq {

    /**
     * API 版本
     */
    private String apiVersion;

    /**
     * 应用 ID
     */
    private String appId;

    /**
     * 类型 ID
     */
    private String typeId;

    /**
     * 环境 ID
     */
    private String envId;

    /**
     * 是否继承
     */
    private boolean inherit;
}