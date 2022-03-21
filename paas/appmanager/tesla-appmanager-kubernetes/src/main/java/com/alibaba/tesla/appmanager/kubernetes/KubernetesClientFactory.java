package com.alibaba.tesla.appmanager.kubernetes;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.tesla.appmanager.api.provider.ClusterProvider;
import com.alibaba.tesla.appmanager.common.exception.AppErrorCode;
import com.alibaba.tesla.appmanager.common.exception.AppException;
import com.alibaba.tesla.appmanager.domain.dto.ClusterDTO;
import io.fabric8.kubernetes.api.model.AuthInfo;
import io.fabric8.kubernetes.api.model.Cluster;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;
import io.fabric8.kubernetes.client.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kubernetes Client 工厂
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
@Service
@Slf4j
public class KubernetesClientFactory {

    private static final String ACCESS_TOKEN = "access-token";

    /**
     * KubernetesClient Map
     */
    private final ConcurrentMap<String, DefaultKubernetesClient> clientMap = new ConcurrentHashMap<>();

    @Autowired
    private ClusterProvider clusterProvider;

    /**
     * 获取指定集群的 Kubernetes Client
     *
     * @param clusterId 集群 ID
     * @return OkHttpClient
     */
    public DefaultKubernetesClient get(String clusterId) {
        ClusterDTO clusterDTO = clusterProvider.get(clusterId);
        if (clusterDTO == null || clusterDTO.getClusterConfig() == null) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    String.format("cannot get cluster info by clusterId %s", clusterId));
        }
        JSONObject clusterConfig = clusterDTO.getClusterConfig();
        String key = clusterId + DigestUtils.md5Hex(clusterConfig.toJSONString());
        DefaultKubernetesClient client = clientMap.get(key);
        if (client != null) {
            return client;
        }

        synchronized (clientMap) {
            // double check
            client = clientMap.get(clusterId);
            if (client != null) {
                return client;
            }

            DefaultKubernetesClient newClient;
            String masterUrl = clusterConfig.getString("masterUrl");
            String oauthToken = clusterConfig.getString("oauthToken");
            String kube = clusterConfig.getString("kube");
            if (StringUtils.isNotEmpty(masterUrl) && StringUtils.isNotEmpty(oauthToken)) {
                Config config = new ConfigBuilder()
                        .withMasterUrl(masterUrl)
                        .withTrustCerts(true)
                        .withOauthToken(oauthToken)
                        .withNamespace(null)
                        .build();
                newClient = new DefaultKubernetesClient(config);
            } else if (StringUtils.isNotEmpty(kube)) {
                Config config = getKubeConfig(kube);
                newClient = new DefaultKubernetesClient(config);
            } else {
                throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                        String.format("cannot find masterUrl/oauthToken/kube in cluster %s configuration", clusterId));
            }
            clientMap.put(key, newClient);
            log.info("kubernetes client for cluster {} has put into client map", clusterId);
            return newClient;
        }
    }

    /**
     * 从 kubeconfig 中获取当前的 Config 对象
     *
     * @param kubeConfigStr kubeconfig 字符串
     * @return Config 对象
     */
    public Config getKubeConfig(String kubeConfigStr) {
        Config config = new ConfigBuilder().build();
        io.fabric8.kubernetes.api.model.Config kubeConfig;
        try {
            kubeConfig = KubeConfigUtils.parseConfigFromString(kubeConfigStr);
        } catch (IOException e) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS, "invalid kubeconfig in cluster configuration");
        }
        NamedContext currentContext = KubeConfigUtils.getCurrentContext(kubeConfig);
        Cluster currentCluster = KubeConfigUtils.getCluster(kubeConfig, currentContext.getContext());
        if (currentCluster != null) {
            config.setMasterUrl(currentCluster.getServer());
            config.setNamespace(currentContext.getContext().getNamespace());
            config.setTrustCerts(currentCluster.getInsecureSkipTlsVerify() != null
                    && currentCluster.getInsecureSkipTlsVerify());
            config.setCaCertData(currentCluster.getCertificateAuthorityData());
            AuthInfo currentAuthInfo = KubeConfigUtils.getUserAuthInfo(kubeConfig, currentContext.getContext());
            if (currentAuthInfo != null) {
                config.setClientCertData(currentAuthInfo.getClientCertificateData());
                config.setClientKeyData(currentAuthInfo.getClientKeyData());
                config.setOauthToken(currentAuthInfo.getToken());
                config.setUsername(currentAuthInfo.getUsername());
                config.setPassword(currentAuthInfo.getPassword());
                if (Utils.isNullOrEmpty(config.getOauthToken()) &&
                        currentAuthInfo.getAuthProvider() != null &&
                        !Utils.isNullOrEmpty(currentAuthInfo.getAuthProvider().getConfig().get(ACCESS_TOKEN))) {
                    config.setOauthToken(currentAuthInfo.getAuthProvider().getConfig().get(ACCESS_TOKEN));
                }
                config.getErrorMessages()
                        .put(401, "Unauthorized! Token may have expired! Please log-in again.");
                config.getErrorMessages()
                        .put(403, "Forbidden! User " + currentContext.getContext().getUser() + " doesn't have permission.");
            }
        }
        return config;
    }
}
