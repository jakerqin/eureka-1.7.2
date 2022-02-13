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

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 *
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;
    
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            // 初始化eureka-server的环境——配置加载进内存、数据中心、运行环境
            initEurekaEnvironment();
            // 读取eureka-server.properties配置文件
            // 将自己作为服务实例，从eureka-client.properties中读取服务实例配置，构造服务实例信息（InstanceInfo）构造服务实例管理器（ApplicationInfoManager）
            // 将自己作为eureka client：从eureka-client.properties中读取eureka client配置，基于InstanceInfo和eureka client配置构造EurekaClient
            // 构造感知eureka server集群的注册表 peerAwareInstanceRegistry
            // 构造eureka server 集群的信息 PeerEurekaNodes
            // 基于eureka server配置、注册表、eureka server集群、服务实例，来构造一个eureka server上下文 EurekaServerContext
            // EurekaServerContext进行初始化 1。更新eureka server集群信息   2。基于eureka server 集群信息初始化注册表
            // 从相邻的eureka server节点拷贝注册表
            // 注册监控
            initEurekaServerContext();

            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");
        // ConfigurationManager是网飞公司内部的一个框架里的
        // ConfigurationManager.getConfigInstance()初始化ConcurrentCompositeConfiguration的实例
        // 也就是配置管理器初始化的一个过程，管理eureka自己的所有配置，读取配置文件里的配置到内存里，供后续eureka-server运行来使用
        // ConfigurationManager.getConfigInstance()使用double check + volatile单例模式
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        if (dataCenter == null) {
            // 如果没有指定数据中心 那就使用默认的名字 default
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            // 如果指定了数据中心，赋值进去
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        // 初始化eureka环境，如果你没有配置的话，默认就给设置为test环境
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     */
    protected void initEurekaServerContext() throws Exception {
        // 第一步: 加载eureka-server.properties文件中的配置
        // eureka-sever.properties文件里，都是一个一个的key=value的很多的配置项，
        // 肯定是将这些key-value格式的配置项加载到内存的Properties对象去存放，Map。
        // 一般来说，如果让我自己来设计这个读取properties文件的配置的代码，也许我就是做到将配置加载到Properties对象中就结束了。
        // 但是eureka-server这里，使用了另外一种思想，没有用大量的常量(频繁变动的项目使用大量的常量通过一个get方法获取比较适合)，
        // 而是针对配置定义了一个接口，接口里通过方法暴露了大量的配置项获取的方法，
        // 直接通过这个接口来获取你需要的配置项，即可。
        // EurekaServerConfig，代表了eureka-server需要的所有的配置项，通过接口定义了大量的方法，让你可以从这里获取所有你需要的配置
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility 为了向后兼容
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);

        // 第二步：初始化ApplicationInfoManager
        ApplicationInfoManager applicationInfoManager = null;

        // 第三步：初始化eureka-server内部的一个eureka-client(用来跟其他的eureka-server节点进行注册和通信)
        if (eurekaClient == null) {
            // 创建ApplicationInfoManager需要先创建一个EurekaInstanceConfig

            // EurekaInstanceConfig，就是将eureka-client.properties文件中的配置加载到ConfigurationManager中去，
            // 然后基于EurekaInstanceConfig对外暴露的接口来获取这个eureka-client.properties文件中的一些配置项的读取，
            // 而且人家提供了所有配置项的默认值
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext()) //判断是否是在云环境部署的
                    ? new CloudInstanceConfig()
                    // 自己部署都是创建这个类
                    // 大致可以认为EurekaInstanceConfig是服务实例相关的一些配置。
                    // eureka server同时也是一个eureka client，因为他可能要向其他的eureka server去进行注册，组成一个eureka server的集群
                    : new MyDataCenterInstanceConfig();

            // EurekaInstanceConfig（代表了一些配置），搞了InstanceInfo（服务实例），基于这俩玩意儿，搞了一个ApplicationInfoManager，作为服务实例的一个管理器
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig,
                    // 主要完成InstanceInfo的创建赋值
                    // InstanceInfo可以认为就是当前这个服务实例的实例本身的信息，直接用了构造器模式，
                    // 用InstanceInfo.Builder来构造一个复杂的代表一个服务实例的InstanceInfo对象。
                    // 核心的思路是，从之前的那个EurekaInstanceConfig中，读取各种各样的服务实例相关的配置信息，
                    // 再构造了几个其他的对象，最终完成了InstanceInfo的构建
                    new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
            // EurekaClientConfig，这个东西也是个接口，对外暴露了获取一大堆的配置项的方法，这里包含的是EurekaClient相关的一些配置项。
            // 也是去读eureka-client.properties里的一些配置，只不过他关注的是跟EurekaInstanceConfig是不一样的(代表了服务实例的一些配置项)
            // 而这里的是关联的这个EurekaClient的一些配置项
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            // 重点** 创建EurekaClient
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }
        // 第三步：处理注册相关的事情
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }

        // 第四步：处理peer节点相关（eureka server集群的信息）的事情
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );

        // 第五步：完成eureka server 上下文（context）的构建以及初始化
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        EurekaServerContextHolder.initialize(serverContext);

        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        // 第六步：从相邻的eureka节点拷贝注册信息
        int registryCount = registry.syncUp();
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        // 第七步：注册所有的监控统计项
        EurekaMonitors.registerAllStats();
    }
    
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date().toString());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date().toString());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
