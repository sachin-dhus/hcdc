package ai.sapper.hcdc.agents.common;

import ai.sapper.hcdc.agents.namenode.HadoopEnvConfig;
import ai.sapper.hcdc.agents.namenode.NameNodeAdminClient;
import ai.sapper.hcdc.agents.namenode.model.NameNodeAgentState;
import ai.sapper.hcdc.agents.namenode.model.NameNodeStatus;
import ai.sapper.hcdc.common.AbstractState;
import ai.sapper.hcdc.common.ConfigReader;
import ai.sapper.hcdc.common.utils.DefaultLogger;
import ai.sapper.hcdc.common.utils.NetUtils;
import ai.sapper.hcdc.core.DistributedLock;
import ai.sapper.hcdc.core.connections.ConnectionManager;
import ai.sapper.hcdc.core.connections.HdfsConnection;
import ai.sapper.hcdc.core.connections.ZookeeperConnection;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Accessors(fluent = true)
public class NameNodeEnv {
    public static Logger LOG = LoggerFactory.getLogger(NameNodeEnv.class);

    private static final String NN_IGNORE_TNX = "%s.IGNORE";

    private final NameNEnvState state = new NameNEnvState();

    private NameNEnvConfig config;
    private HierarchicalConfiguration<ImmutableNode> configNode;
    private ConnectionManager connectionManager;
    private HdfsConnection hdfsConnection;
    private ZkStateManager stateManager;
    private List<InetAddress> hostIPs;
    private HadoopEnvConfig hadoopConfig;
    private NameNodeAdminClient adminClient;
    private final Map<String, DistributedLock> locks = new HashMap<>();

    private final NameNodeAgentState.AgentState agentState = new NameNodeAgentState.AgentState();

    public synchronized NameNodeEnv init(@NonNull HierarchicalConfiguration<ImmutableNode> xmlConfig) throws NameNodeError {
        try {
            if (state.isAvailable()) return this;

            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    NameNodeEnv.ENameNEnvState state = NameNodeEnv.dispose();
                    DefaultLogger.LOG.warn(String.format("Edit Log Processor Shutdown...[state=%s]", state.name()));
                }
            });
            configNode = xmlConfig.configurationAt(NameNEnvConfig.Constants.__CONFIG_PATH);

            this.config = new NameNEnvConfig(xmlConfig);
            this.config.read();

            hostIPs = NetUtils.getInetAddresses();

            connectionManager = new ConnectionManager();
            connectionManager.init(xmlConfig, config.connectionConfigPath);

            if (config().readHadoopConfig) {
                hdfsConnection = connectionManager.getConnection(config.hdfsAdminConnection, HdfsConnection.class);
                if (hdfsConnection == null) {
                    throw new ConfigurationException("HDFS Admin connection not found.");
                }
                if (!hdfsConnection.isConnected()) hdfsConnection.connect();

                hadoopConfig = new HadoopEnvConfig(config.hadoopHome,
                        config.hadoopConfFile,
                        config.hadoopNamespace,
                        config.hadoopInstanceName);
                hadoopConfig.read();
                adminClient = new NameNodeAdminClient(hadoopConfig.nameNodeAdminUrl(), config.hadoopUseSSL);
                NameNodeStatus status = adminClient().status();
                if (status != null) {
                    agentState.parseState(status.getState());
                } else {
                    throw new NameNodeError(String.format("Error fetching NameNode status. [url=%s]", adminClient.url()));
                }
            }

            stateManager = config.stateManagerClass.newInstance();
            stateManager.init(configNode, connectionManager, config.module, config.instance);

            readLocks();
            DistributedLock lock = lock(ZkStateManager.Constants.LOCK_REPLICATION);
            if (lock == null) {
                throw new ConfigurationException(
                        String.format("Replication Lock not defined. [name=%s]",
                                ZkStateManager.Constants.LOCK_REPLICATION));
            }
            stateManager.withReplicationLock(lock);

            state.state(ENameNEnvState.Initialized);

            stateManager.heartbeat(config.instance, agentState);

            return this;
        } catch (Throwable t) {
            state.error(t);
            throw new NameNodeError(t);
        }
    }

    private void readLocks() throws Exception {
        List<HierarchicalConfiguration<ImmutableNode>> nodes = configNode.configurationsAt(NameNEnvConfig.Constants.CONFIG_LOCK);
        for (HierarchicalConfiguration<ImmutableNode> node : nodes) {
            String name = node.getString(NameNEnvConfig.Constants.CONFIG_LOCK_NAME);
            String conn = node.getString(NameNEnvConfig.Constants.CONFIG_LOCK_CONN);
            String path = name;
            if (node.containsKey(NameNEnvConfig.Constants.CONFIG_LOCK_NODE)) {
                path = node.getString(NameNEnvConfig.Constants.CONFIG_LOCK_NODE);
            }
            ZookeeperConnection connection = connectionManager.getConnection(conn, ZookeeperConnection.class);
            DistributedLock lock = new DistributedLock(config.module, path, stateManager.basePath()).withConnection(connection);
            locks.put(name, lock);
        }
    }

    public ENameNEnvState error(@NonNull Throwable t) {
        state.error(t);
        return state.state();
    }

    public synchronized ENameNEnvState stop() {
        if (agentState.state() == NameNodeAgentState.EAgentState.Active
                || agentState.state() == NameNodeAgentState.EAgentState.StandBy) {
            agentState.state(NameNodeAgentState.EAgentState.Stopped);
        }
        if (state.isAvailable()) {
            try {
                stateManager.heartbeat(config.instance, agentState);
            } catch (Exception ex) {
                DefaultLogger.LOG.error(ex.getLocalizedMessage());
                DefaultLogger.LOG.debug(DefaultLogger.stacktrace(ex));
            }
            state.state(ENameNEnvState.Disposed);
        }

        return state.state();
    }

    public String ignoreTnxKey() {
        return String.format(NN_IGNORE_TNX, config.hadoopNamespace);
    }

    public String module() {
        return config.module();
    }

    public String instance() {
        return config.instance;
    }

    public String source() {
        return config.source;
    }

    public String hadoopHome() {
        return config.hadoopHome;
    }

    public String hadoopConfigFile() {
        return config.hadoopConfFile;
    }

    public DistributedLock lock(String name) {
        if (locks.containsKey(name)) return locks.get(name);
        return null;
    }

    private static final NameNodeEnv __instance = new NameNodeEnv();

    public static NameNodeEnv setup(@NonNull HierarchicalConfiguration<ImmutableNode> config) throws NameNodeError {
        synchronized (__instance) {
            return __instance.init(config);
        }
    }

    public static ENameNEnvState dispose() {
        synchronized (__instance) {
            return __instance.stop();
        }
    }

    public static NameNodeEnv get() {
        Preconditions.checkState(__instance.state.isAvailable());
        return __instance;
    }

    public static ConnectionManager connectionManager() {
        return get().connectionManager;
    }

    public static ZkStateManager stateManager() {
        return get().stateManager;
    }

    public static DistributedLock globalLock() {
        return get().lock(NameNEnvConfig.Constants.LOCK_GLOBAL);
    }

    public enum ENameNEnvState {
        Unknown, Initialized, Error, Disposed
    }

    @Getter
    @Setter
    @Accessors(fluent = true)
    public static class NameNEnvState extends AbstractState<ENameNEnvState> {

        public NameNEnvState() {
            super(ENameNEnvState.Error);
            state(ENameNEnvState.Unknown);
        }

        public boolean isAvailable() {
            return (state() == ENameNEnvState.Initialized);
        }
    }

    @Getter
    @Setter
    @Accessors(fluent = true)
    public static class NameNEnvConfig extends ConfigReader {
        private static class Constants {
            private static final String __CONFIG_PATH = "agent";
            private static final String CONFIG_MODULE = "module";
            private static final String CONFIG_INSTANCE = "instance";
            private static final String CONFIG_SOURCE_NAME = "source";
            private static final String CONFIG_STATE_MANAGER_TYPE = "stateManagerClass";
            private static final String CONFIG_CONNECTIONS = "connections.path";
            private static final String CONFIG_CONNECTION_HDFS = "connections.hdfs-admin";

            private static final String CONFIG_HADOOP_HOME = "hadoop.home";
            private static final String CONFIG_HADOOP_INSTANCE = "hadoop.instance";
            private static final String CONFIG_HADOOP_NAMESPACE = "hadoop.namespace";

            private static final String CONFIG_HADOOP_CONFIG = "hadoop.config";

            private static final String HDFS_NN_USE_HTTPS = "useSSL";
            private static final String CONFIG_LOCKS = "locks";
            private static final String CONFIG_LOCK = String.format("%s.lock", CONFIG_LOCKS);
            private static final String CONFIG_LOCK_NAME = "name";
            private static final String CONFIG_LOCK_CONN = "connection";
            private static final String CONFIG_LOCK_NODE = "lock-node";
            private static final String LOCK_GLOBAL = "global";
            private static final String CONFIG_LOAD_HADOOP = "needHadoop";
        }

        private String module;
        private String instance;
        private String source;
        private String connectionConfigPath;
        private String hdfsAdminConnection;
        private String hadoopNamespace;
        private String hadoopInstanceName;
        private String hadoopHome;
        private String hadoopConfFile;
        private boolean hadoopUseSSL = true;
        private boolean readHadoopConfig = true;
        private Class<? extends ZkStateManager> stateManagerClass = ZkStateManager.class;

        public NameNEnvConfig(@NonNull HierarchicalConfiguration<ImmutableNode> config) {
            super(config, Constants.__CONFIG_PATH);
        }

        public void read() throws ConfigurationException {
            if (get() == null) {
                throw new ConfigurationException("HDFS Configuration not set or is NULL");
            }
            try {
                module = get().getString(Constants.CONFIG_MODULE);
                if (Strings.isNullOrEmpty(module)) {
                    throw new ConfigurationException(
                            String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_MODULE));
                }
                instance = get().getString(Constants.CONFIG_INSTANCE);
                if (Strings.isNullOrEmpty(instance)) {
                    throw new ConfigurationException(
                            String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_INSTANCE));
                }
                source = get().getString(Constants.CONFIG_SOURCE_NAME);
                if (Strings.isNullOrEmpty(source)) {
                    throw new ConfigurationException(
                            String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_SOURCE_NAME));
                }
                String ss = get().getString(Constants.CONFIG_LOAD_HADOOP);
                if (!Strings.isNullOrEmpty(ss)) {
                    readHadoopConfig = Boolean.parseBoolean(ss);
                }
                String s = get().getString(Constants.CONFIG_STATE_MANAGER_TYPE);
                if (!Strings.isNullOrEmpty(s)) {
                    stateManagerClass = (Class<? extends ZkStateManager>) Class.forName(s);
                }
                connectionConfigPath = get().getString(Constants.CONFIG_CONNECTIONS);
                if (Strings.isNullOrEmpty(connectionConfigPath)) {
                    throw new ConfigurationException(
                            String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_CONNECTIONS));
                }
                if (readHadoopConfig)
                    readHadoopConfigs();
            } catch (Throwable t) {
                throw new ConfigurationException("Error processing HDFS configuration.", t);
            }
        }

        private void readHadoopConfigs() throws Exception {
            hadoopNamespace = get().getString(Constants.CONFIG_HADOOP_NAMESPACE);
            if (Strings.isNullOrEmpty(hadoopNamespace)) {
                throw new ConfigurationException(
                        String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_HADOOP_NAMESPACE));
            }
            hadoopInstanceName = get().getString(Constants.CONFIG_HADOOP_INSTANCE);
            if (Strings.isNullOrEmpty(hadoopInstanceName)) {
                throw new ConfigurationException(
                        String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_INSTANCE));
            }

            hdfsAdminConnection = get().getString(Constants.CONFIG_CONNECTION_HDFS);
            if (Strings.isNullOrEmpty(hdfsAdminConnection)) {
                throw new ConfigurationException(
                        String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_CONNECTION_HDFS));
            }
            hadoopHome = get().getString(Constants.CONFIG_HADOOP_HOME);
            if (Strings.isNullOrEmpty(hadoopHome)) {
                hadoopHome = System.getProperty("HADOOP_HOME");
                if (Strings.isNullOrEmpty(hadoopHome))
                    throw new ConfigurationException(
                            String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_HADOOP_HOME));
            }
            hadoopConfFile = get().getString(Constants.CONFIG_HADOOP_CONFIG);
            if (Strings.isNullOrEmpty(hadoopConfFile)) {
                throw new ConfigurationException(
                        String.format("NameNode Agent Configuration Error: missing [%s]", Constants.CONFIG_HADOOP_CONFIG));
            }
            String s = get().getString(Constants.HDFS_NN_USE_HTTPS);
            if (!Strings.isNullOrEmpty(s)) {
                hadoopUseSSL = Boolean.parseBoolean(s);
            }
        }
    }
}
