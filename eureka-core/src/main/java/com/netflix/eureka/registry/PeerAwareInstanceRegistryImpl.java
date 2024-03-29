/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.AmazonInfo.MetaDataKey;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.registry.rule.DownOrStartingRule;
import com.netflix.eureka.registry.rule.FirstMatchWinsCompositeRule;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.registry.rule.LeaseExistsRule;
import com.netflix.eureka.registry.rule.OverrideExistsRule;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles replication of all operations to {@link AbstractInstanceRegistry} to peer
 * <em>Eureka</em> nodes to keep them all in sync.
 *
 * <p>
 * Primary operations that are replicated are the
 * <em>Registers,Renewals,Cancels,Expirations and Status Changes</em>
 * </p>
 *
 * <p>
 * When the eureka server starts up it tries to fetch all the registry
 * information from the peer eureka nodes.If for some reason this operation
 * fails, the server does not allow the user to get the registry information for
 * a period specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}.
 * </p>
 *
 * <p>
 * One important thing to note about <em>renewals</em>.If the renewal drops more
 * than the specified threshold as specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalPercentThreshold()} within a period of
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalThresholdUpdateIntervalMs()}, eureka
 * perceives this as a danger and stops expiring instances.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Singleton
public class PeerAwareInstanceRegistryImpl extends AbstractInstanceRegistry implements PeerAwareInstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PeerAwareInstanceRegistryImpl.class);

    private static final String US_EAST_1 = "us-east-1";
    private static final int PRIME_PEER_NODES_RETRY_MS = 30000;

    private long startupTime = 0;
    private boolean peerInstancesTransferEmptyOnStartup = true;

    public enum Action {
        Heartbeat, Register, Cancel, StatusUpdate, DeleteStatusOverride;

        private com.netflix.servo.monitor.Timer timer = Monitors.newTimer(this.name());

        public com.netflix.servo.monitor.Timer getTimer() {
            return this.timer;
        }
    }

    private static final Comparator<Application> APP_COMPARATOR = new Comparator<Application>() {
        public int compare(Application l, Application r) {
            return l.getName().compareTo(r.getName());
        }
    };

    private final MeasuredRate numberOfReplicationsLastMin;

    protected final EurekaClient eurekaClient;
    protected volatile PeerEurekaNodes peerEurekaNodes;

    private final InstanceStatusOverrideRule instanceStatusOverrideRule;

    private Timer timer = new Timer(
            "ReplicaAwareInstanceRegistry - RenewalThresholdUpdater", true);

    @Inject
    public PeerAwareInstanceRegistryImpl(
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            EurekaClient eurekaClient
    ) {
        super(serverConfig, clientConfig, serverCodecs);
        this.eurekaClient = eurekaClient;
        this.numberOfReplicationsLastMin = new MeasuredRate(1000 * 60 * 1);
        // We first check if the instance is STARTING or DOWN, then we check explicit overrides,
        // then we check the status of a potentially existing lease.
        this.instanceStatusOverrideRule = new FirstMatchWinsCompositeRule(new DownOrStartingRule(),
                new OverrideExistsRule(overriddenInstanceStatusMap), new LeaseExistsRule());
    }

    @Override
    protected InstanceStatusOverrideRule getInstanceInfoOverrideRule() {
        return this.instanceStatusOverrideRule;
    }

    @Override
    public void init(PeerEurekaNodes peerEurekaNodes) throws Exception {
        this.numberOfReplicationsLastMin.start();
        this.peerEurekaNodes = peerEurekaNodes;
        initializedResponseCache();
        // 默认是15分钟，会跑一次定时任务，算一下服务实例的数量，如果从别的eureka server拉取到的服务实例的数量，
        // 大于当前的服务实例的数量，会重新计算每分钟期望心跳数，主要是跟其他的eureka server做一下同步
        scheduleRenewalThresholdUpdateTask();
        initRemoteRegionRegistry();

        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry :", e);
        }
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        try {
            DefaultMonitorRegistry.getInstance().unregister(Monitors.newObjectMonitor(this));
        } catch (Throwable t) {
            logger.error("Cannot shutdown monitor registry", t);
        }
        try {
            peerEurekaNodes.shutdown();
        } catch (Throwable t) {
            logger.error("Cannot shutdown ReplicaAwareInstanceRegistry", t);
        }
        numberOfReplicationsLastMin.stop();

        super.shutdown();
    }

    /**
     * Schedule the task that updates <em>renewal threshold</em> periodically.
     * The renewal threshold would be used to determine if the renewals drop
     * dramatically because of network partition and to protect expiring too
     * many instances at a time.
     *
     */
    private void scheduleRenewalThresholdUpdateTask() {
        timer.schedule(new TimerTask() {
                           @Override
                           public void run() {
                               updateRenewalThreshold();
                           }
                       }, serverConfig.getRenewalThresholdUpdateIntervalMs(), // 15分钟
                serverConfig.getRenewalThresholdUpdateIntervalMs());
    }

    /**
     * Populates the registry information from a peer eureka node. This
     * operation fails over to other nodes until the list is exhausted if the
     * communication fails.
     * 从相邻的eureka server节点，拷贝过来注册表，如果是自己本地还没注册的服务实例，就在自己本地注册一下
     * 会记录一下从别的eureka server拉取过来的服务实例的数量，将这个服务实例的数量，就作为自己eureka server本地初始化的这么一个服务实例的数量
     */
    @Override
    public int syncUp() {
        // Copy entire entry from neighboring DS node
        int count = 0;
        // serverConfig.getRegistrySyncRetries() 默认5次重试
        for (int i = 0; ((i < serverConfig.getRegistrySyncRetries()) && (count == 0)); i++) {
            if (i > 0) {  // 说明前几次拉取失败，可能本地的eureka client还没有从相邻eureka serve上拉取来数据
                try {
                    // 这里休眠一段时间，等待eureka client从相邻eureka serve上进行注册并拉取来全量的注册表信息
                    Thread.sleep(serverConfig.getRegistrySyncRetryWaitMs());
                } catch (InterruptedException e) {
                    logger.warn("Interrupted during registry transfer..");
                    break;
                }
            }
            // 获取本地的注册表信息，如果没有那么count就一直是0，上面for循环会一直走
            Applications apps = eurekaClient.getApplications();
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    try {
                        if (isRegisterable(instance)) {
                            register(instance, instance.getLeaseInfo().getDurationInSecs(), true);
                            count++;
                        }
                    } catch (Throwable t) {
                        logger.error("During DS init copy", t);
                    }
                }
            }
        }
        return count;
    }

    @Override
    public void openForTraffic(ApplicationInfoManager applicationInfoManager, int count) {
        // Renewals happen every 30 seconds and for a minute it should be a factor of 2.
        // 每个服务实例每30秒发送一次心跳，于是一分钟一个服务实例应该发送2次心跳，1分钟内我期望获取到的心跳的次数，应该是 num * 2
        // 居然硬编码了，没想象中的强，如果30s发送一次心跳的配置变更了，那么这个代码就是个bug
        // 优化：count * （60 / 心跳时间间隔）
        this.expectedNumberOfRenewsPerMin = count * 2;
        // 初始化numberOfRenewsPerMinThreshold 期望1分钟得有多少次心跳
        // 默认情况下 count * 2 * 0.85
        this.numberOfRenewsPerMinThreshold =
                (int) (this.expectedNumberOfRenewsPerMin * serverConfig.getRenewalPercentThreshold()); // 0.85
        logger.info("Got " + count + " instances from neighboring DS node");
        logger.info("Renew threshold is: " + numberOfRenewsPerMinThreshold);
        this.startupTime = System.currentTimeMillis();
        if (count > 0) {
            this.peerInstancesTransferEmptyOnStartup = false;
        }
        DataCenterInfo.Name selfName = applicationInfoManager.getInfo().getDataCenterInfo().getName();
        boolean isAws = Name.Amazon == selfName;
        if (isAws && serverConfig.shouldPrimeAwsReplicaConnections()) {
            logger.info("Priming AWS connections for all replicas..");
            primeAwsReplicas(applicationInfoManager);
        }
        logger.info("Changing status to UP");
        applicationInfoManager.setInstanceStatus(InstanceStatus.UP);
        // 启动一个线程 服务实例自动故障感知及服务自动摘除功能逻辑
        // 感觉这个定时任务应该放到PeerAwareInstanceRegistryImpl初始化的时候，感觉更符合软件设计
        super.postInit();
    }

    /**
     * Prime connections for Aws replicas.
     * <p>
     * Sometimes when the eureka servers comes up, AWS firewall may not allow
     * the network connections immediately. This will cause the outbound
     * connections to fail, but the inbound connections continue to work. What
     * this means is the clients would have switched to this node (after EIP
     * binding) and so the other eureka nodes will expire all instances that
     * have been switched because of the lack of outgoing heartbeats from this
     * instance.
     * </p>
     * <p>
     * The best protection in this scenario is to block and wait until we are
     * able to ping all eureka nodes successfully atleast once. Until then we
     * won't open up the traffic.
     * </p>
     */
    private void primeAwsReplicas(ApplicationInfoManager applicationInfoManager) {
        boolean areAllPeerNodesPrimed = false;
        while (!areAllPeerNodesPrimed) {
            String peerHostName = null;
            try {
                Application eurekaApps = this.getApplication(applicationInfoManager.getInfo().getAppName(), false);
                if (eurekaApps == null) {
                    areAllPeerNodesPrimed = true;
                    logger.info("No peers needed to prime.");
                    return;
                }
                for (PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                    for (InstanceInfo peerInstanceInfo : eurekaApps.getInstances()) {
                        LeaseInfo leaseInfo = peerInstanceInfo.getLeaseInfo();
                        // If the lease is expired - do not worry about priming
                        if (System.currentTimeMillis() > (leaseInfo
                                .getRenewalTimestamp() + (leaseInfo
                                .getDurationInSecs() * 1000))
                                + (2 * 60 * 1000)) {
                            continue;
                        }
                        peerHostName = peerInstanceInfo.getHostName();
                        logger.info("Trying to send heartbeat for the eureka server at {} to make sure the " +
                                "network channels are open", peerHostName);
                        // Only try to contact the eureka nodes that are in this instance's registry - because
                        // the other instances may be legitimately down
                        if (peerHostName.equalsIgnoreCase(new URI(node.getServiceUrl()).getHost())) {
                            node.heartbeat(
                                    peerInstanceInfo.getAppName(),
                                    peerInstanceInfo.getId(),
                                    peerInstanceInfo,
                                    null,
                                    true);
                        }
                    }
                }
                areAllPeerNodesPrimed = true;
            } catch (Throwable e) {
                logger.error("Could not contact " + peerHostName, e);
                try {
                    Thread.sleep(PRIME_PEER_NODES_RETRY_MS);
                } catch (InterruptedException e1) {
                    logger.warn("Interrupted while priming : ", e1);
                    areAllPeerNodesPrimed = true;
                }
            }
        }
    }

    /**
     * Checks to see if the registry access is allowed or the server is in a
     * situation where it does not all getting registry information. The server
     * does not return registry information for a period specified in
     * {@link EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot
     * get the registry information from the peer eureka nodes at start up.
     *
     * @return false - if the instances count from a replica transfer returned
     *         zero and if the wait time has not elapsed, otherwise returns true
     */
    @Override
    public boolean shouldAllowAccess(boolean remoteRegionRequired) {
        if (this.peerInstancesTransferEmptyOnStartup) {
            if (!(System.currentTimeMillis() > this.startupTime + serverConfig.getWaitTimeInMsWhenSyncEmpty())) {
                return false;
            }
        }
        if (remoteRegionRequired) {
            for (RemoteRegionRegistry remoteRegionRegistry : this.regionNameVSRemoteRegistry.values()) {
                if (!remoteRegionRegistry.isReadyForServingData()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean shouldAllowAccess() {
        return shouldAllowAccess(true);
    }

    /**
     * @deprecated use {@link com.netflix.eureka.cluster.PeerEurekaNodes#getPeerEurekaNodes()} directly.
     *
     * Gets the list of peer eureka nodes which is the list to replicate
     * information to.
     *
     * @return the list of replica nodes.
     */
    @Deprecated
    public List<PeerEurekaNode> getReplicaNodes() {
        return Collections.unmodifiableList(peerEurekaNodes.getPeerEurekaNodes());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#cancel(java.lang.String,
     * java.lang.String, long, boolean)
     */
    @Override
    public boolean cancel(final String appName, final String id,
                          final boolean isReplication) {
        if (super.cancel(appName, id, isReplication)) {
            replicateToPeers(Action.Cancel, appName, id, null, null, isReplication);
            synchronized (lock) {
                if (this.expectedNumberOfRenewsPerMin > 0) {
                    // Since the client wants to cancel it, reduce the threshold (1 for 30 seconds, 2 for a minute)
                    // 硬编码， 简直是个笑话
                    this.expectedNumberOfRenewsPerMin = this.expectedNumberOfRenewsPerMin - 2;
                    // 服务实例下线，更新每分钟期望的心跳数
                    this.numberOfRenewsPerMinThreshold =
                            (int) (this.expectedNumberOfRenewsPerMin * serverConfig.getRenewalPercentThreshold());
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Registers the information about the {@link InstanceInfo} and replicates
     * this information to all peer eureka nodes. If this is replication event
     * from other replica nodes then it is not replicated.
     *
     * @param info
     *            the {@link InstanceInfo} to be registered and replicated.
     * @param isReplication
     *            true if this is a replication event from other replica nodes,
     *            false otherwise.
     */
    @Override
    public void register(final InstanceInfo info, final boolean isReplication) {
        int leaseDuration = Lease.DEFAULT_DURATION_IN_SECS;
        if (info.getLeaseInfo() != null && info.getLeaseInfo().getDurationInSecs() > 0) {
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }
        // **进行注册
        super.register(info, leaseDuration, isReplication);
        // 将这次注册请求，同步到其他所有的eureka server上去
        replicateToPeers(Action.Register, info.getAppName(), info.getId(), info, null, isReplication);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#renew(java.lang.String,
     * java.lang.String, long, boolean)
     */
    public boolean renew(final String appName, final String id, final boolean isReplication) {
        if (super.renew(appName, id, isReplication)) {
            replicateToPeers(Action.Heartbeat, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#statusUpdate(java.lang.String,
     * java.lang.String, com.netflix.appinfo.InstanceInfo.InstanceStatus,
     * java.lang.String, boolean)
     */
    @Override
    public boolean statusUpdate(final String appName, final String id,
                                final InstanceStatus newStatus, String lastDirtyTimestamp,
                                final boolean isReplication) {
        if (super.statusUpdate(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            replicateToPeers(Action.StatusUpdate, appName, id, null, newStatus, isReplication);
            return true;
        }
        return false;
    }

    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        if (super.deleteStatusOverride(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            replicateToPeers(Action.DeleteStatusOverride, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }

    /**
     * Replicate the <em>ASG status</em> updates to peer eureka nodes. If this
     * event is a replication from other nodes, then it is not replicated to
     * other nodes.
     *
     * @param asgName the asg name for which the status needs to be replicated.
     * @param newStatus the {@link ASGStatus} information that needs to be replicated.
     * @param isReplication true if this is a replication event from other nodes, false otherwise.
     */
    @Override
    public void statusUpdate(final String asgName, final ASGStatus newStatus, final boolean isReplication) {
        // If this is replicated from an other node, do not try to replicate again.
        if (isReplication) {
            return;
        }
        for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
            replicateASGInfoToReplicaNodes(asgName, newStatus, node);

        }
    }

    @Override
    public boolean isLeaseExpirationEnabled() {
        // isSelfPreservationModeEnabled() 默认是true
        if (!isSelfPreservationModeEnabled()) {
            // The self preservation mode is disabled, hence allowing the instances to expire.
            return true;
        }
        // numberOfRenewsPerMinThreshold --> 我期望的是一分钟要有多少次心跳发送过来，假设是100次
        // getNumOfRenewsInLastMin() --->    上一分钟所有服务实例一共发送过来多少次心跳，假设是102次
        // 如果上一分钟的心跳次数(102次) > 期望的100次，返回true，那么就可以清理故障的服务实例
        // 如果上一分钟中心跳次数太多（20次） < 期望的100次，此时会返回false

        // getNumOfRenewsInLastMin() 记录实际上一分钟的心跳数，这一块逻辑应在心跳续约中，应该是renews()
        return numberOfRenewsPerMinThreshold > 0 && getNumOfRenewsInLastMin() > numberOfRenewsPerMinThreshold;
    }

    /**
     * Checks to see if the self-preservation mode is enabled.
     *
     * <p>
     * The self-preservation mode is enabled if the expected number of renewals
     * per minute {@link #getNumOfRenewsInLastMin()} is lesser than the expected
     * threshold which is determined by {@link #getNumOfRenewsPerMinThreshold()}
     * . Eureka perceives this as a danger and stops expiring instances as this
     * is most likely because of a network event. The mode is disabled only when
     * the renewals get back to above the threshold or if the flag
     * {@link EurekaServerConfig#shouldEnableSelfPreservation()} is set to
     * false.
     * </p>
     *
     * @return true if the self-preservation mode is enabled, false otherwise.
     */
    @Override
    public boolean isSelfPreservationModeEnabled() {
        return serverConfig.shouldEnableSelfPreservation();
    }

    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Updates the <em>renewal threshold</em> based on the current number of
     * renewals. The threshold is a percentage as specified in
     * {@link EurekaServerConfig#getRenewalPercentThreshold()} of renewals
     * received per minute {@link #getNumOfRenewsInLastMin()}.
     * 如果从别的eureka server拉取到的服务实例的数量，大于当前的服务实例的数量，会重新计算每分钟期望心跳数，主要是跟其他的eureka server做一下同步
     */
    private void updateRenewalThreshold() {
        try {
            // 将自己作为eureka client，从其他的eureka server拉取注册表
            // 合并到自己的本地去
            Applications apps = eurekaClient.getApplications();
            int count = 0;
            // 将别的服务拉取的服务实例的数量作为count
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    if (this.isRegisterable(instance)) {
                        ++count;
                    }
                }
            }
            synchronized (lock) {
                // Update threshold only if the threshold is greater than the
                // current expected threshold of if the self preservation is disabled.
                // 如果从别的eureka server拉取到的服务实例的数量，大于当前的服务实例的数量，会重新计算每分钟期望心跳数
                if ((count * 2) > (serverConfig.getRenewalPercentThreshold() * numberOfRenewsPerMinThreshold)
                        || (!this.isSelfPreservationModeEnabled())) {
                    this.expectedNumberOfRenewsPerMin = count * 2;
                    this.numberOfRenewsPerMinThreshold = (int) ((count * 2) * serverConfig.getRenewalPercentThreshold());
                }
            }
            logger.info("Current renewal threshold is : {}", numberOfRenewsPerMinThreshold);
        } catch (Throwable e) {
            logger.error("Cannot update renewal threshold", e);
        }
    }

    /**
     * Gets the list of all {@link Applications} from the registry in sorted
     * lexical order of {@link Application#getName()}.
     *
     * @return the list of {@link Applications} in lexical order.
     */
    @Override
    public List<Application> getSortedApplications() {
        List<Application> apps = new ArrayList<Application>(getApplications().getRegisteredApplications());
        Collections.sort(apps, APP_COMPARATOR);
        return apps;
    }

    /**
     * Gets the number of <em>renewals</em> in the last minute.
     *
     * @return a long value representing the number of <em>renewals</em> in the last minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfReplicationsInLastMin",
            description = "Number of total replications received in the last minute",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    public long getNumOfReplicationsInLastMin() {
        return numberOfReplicationsLastMin.getCount();
    }

    /**
     * Checks if the number of renewals is lesser than threshold.
     *
     * @return 0 if the renewals are greater than threshold, 1 otherwise.
     */
    @com.netflix.servo.annotations.Monitor(name = "isBelowRenewThreshold", description = "0 = false, 1 = true",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    @Override
    public int isBelowRenewThresold() {
        if ((getNumOfRenewsInLastMin() <= numberOfRenewsPerMinThreshold)
                &&
                ((this.startupTime > 0) && (System.currentTimeMillis() > this.startupTime + (serverConfig.getWaitTimeInMsWhenSyncEmpty())))) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Checks if an instance is registerable in this region. Instances from other regions are rejected.
     *
     * @param instanceInfo  th instance info information of the instance
     * @return true, if it can be registered in this server, false otherwise.
     */
    public boolean isRegisterable(InstanceInfo instanceInfo) {
        DataCenterInfo datacenterInfo = instanceInfo.getDataCenterInfo();
        String serverRegion = clientConfig.getRegion();
        if (AmazonInfo.class.isInstance(datacenterInfo)) {
            AmazonInfo info = AmazonInfo.class.cast(instanceInfo.getDataCenterInfo());
            String availabilityZone = info.get(MetaDataKey.availabilityZone);
            // Can be null for dev environments in non-AWS data center
            if (availabilityZone == null && US_EAST_1.equalsIgnoreCase(serverRegion)) {
                return true;
            } else if ((availabilityZone != null) && (availabilityZone.contains(serverRegion))) {
                // If in the same region as server, then consider it registerable
                return true;
            }
        }
        return true; // Everything non-amazon is registrable.
    }

    /**
     * Replicates all eureka actions to peer eureka nodes except for replication
     * traffic to this node.
     *
     */
    private void replicateToPeers(Action action, String appName, String id,
                                  InstanceInfo info /* optional */,
                                  InstanceStatus newStatus /* optional */, boolean isReplication) {
        // 上面的isReplication在当前eureka serve收到那就是false
        // 因为当前serve执行了注册、心跳等心跳之后会同步给其他eureka serve
        // 但是其他eureka serve收到这个请求时这个isReplication参数是true（在同步发送请求的时候会把这个参数设置成true）
        // 因为它不能再向其他serve进行同步了，不然就是死循环了
        Stopwatch tracer = action.getTimer().start();
        try {
            if (isReplication) {
                numberOfReplicationsLastMin.increment();
            }
            // If it is a replication already, do not replicate again as this will create a poison replication
            if (peerEurekaNodes == Collections.EMPTY_LIST || isReplication) {
                return;
            }

            for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                // If the url represents this host, do not replicate to yourself.
                if (peerEurekaNodes.isThisMyUrl(node.getServiceUrl())) {
                    continue;
                }
                // 同步
                replicateInstanceActionsToPeers(action, appName, id, info, newStatus, node);
            }
        } finally {
            tracer.stop();
        }
    }

    /**
     * Replicates all instance changes to peer eureka nodes except for
     * replication traffic to this node.
     *
     */
    private void replicateInstanceActionsToPeers(Action action, String appName,
                                                 String id, InstanceInfo info, InstanceStatus newStatus,
                                                 PeerEurekaNode node) {
        try {
            InstanceInfo infoFromRegistry = null;
            CurrentRequestVersion.set(Version.V2);
            switch (action) {
                case Cancel:
                    node.cancel(appName, id);
                    break;
                case Heartbeat:
                    InstanceStatus overriddenStatus = overriddenInstanceStatusMap.get(id);
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.heartbeat(appName, id, infoFromRegistry, overriddenStatus, false);
                    break;
                case Register:
                    node.register(info);
                    break;
                case StatusUpdate:
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.statusUpdate(appName, id, newStatus, infoFromRegistry);
                    break;
                case DeleteStatusOverride:
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.deleteStatusOverride(appName, id, infoFromRegistry);
                    break;
            }
        } catch (Throwable t) {
            logger.error("Cannot replicate information to {} for action {}", node.getServiceUrl(), action.name(), t);
        }
    }

    /**
     * Replicates all ASG status changes to peer eureka nodes except for
     * replication traffic to this node.
     */
    private void replicateASGInfoToReplicaNodes(final String asgName,
                                                final ASGStatus newStatus, final PeerEurekaNode node) {
        CurrentRequestVersion.set(Version.V2);
        try {
            node.statusUpdate(asgName, newStatus);
        } catch (Throwable e) {
            logger.error("Cannot replicate ASG status information to {}", node.getServiceUrl(), e);
        }
    }

    @Override
    @com.netflix.servo.annotations.Monitor(name = "localRegistrySize",
            description = "Current registry size", type = DataSourceType.GAUGE)
    public long getLocalRegistrySize() {
        return super.getLocalRegistrySize();
    }
}
