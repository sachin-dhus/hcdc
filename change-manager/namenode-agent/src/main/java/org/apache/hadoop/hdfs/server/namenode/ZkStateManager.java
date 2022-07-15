package org.apache.hadoop.hdfs.server.namenode;

import ai.sapper.hcdc.agents.namenode.model.NameNodeAgentState;
import ai.sapper.hcdc.agents.namenode.model.NameNodeTxState;
import ai.sapper.hcdc.common.utils.JSONUtils;
import ai.sapper.hcdc.common.utils.PathUtils;
import ai.sapper.hcdc.core.connections.ConnectionManager;
import ai.sapper.hcdc.core.connections.ZookeeperConnection;
import ai.sapper.hcdc.core.connections.state.BlockTnxDelta;
import ai.sapper.hcdc.core.connections.state.DFSBlockState;
import ai.sapper.hcdc.core.connections.state.DFSFileState;
import ai.sapper.hcdc.core.connections.state.StateManagerError;
import ai.sapper.hcdc.core.filters.DomainManager;
import ai.sapper.hcdc.core.model.Heartbeat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.curator.framework.CuratorFramework;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Getter
@Accessors(fluent = true)
public class ZkStateManager {
    public static class Constants {
        public static final String ZK_PATH_SUFFIX = "/agent/namenode";
        public static final String ZK_PATH_HEARTBEAT = "/heartbeat";
        public static final String ZK_PATH_FILES = "/files";
    }

    private ZookeeperConnection connection;
    private ZkStateManagerConfig config;
    private String zkPath;
    private NameNodeTxState agentTxState;
    private final ObjectMapper mapper = new ObjectMapper();

    public ZkStateManager init(@NonNull HierarchicalConfiguration<ImmutableNode> xmlConfig,
                               @NonNull ConnectionManager manger, @NonNull String namespace) throws StateManagerError {
        try {
            config = new ZkStateManagerConfig(xmlConfig);
            config.read();

            connection = manger.getConnection(config.zkConnection, ZookeeperConnection.class);
            if (!connection.isConnected()) connection.connect();
            CuratorFramework client = connection().client();

            zkPath = PathUtils.formatZkPath(String.format("%s%s/%s", basePath(), Constants.ZK_PATH_SUFFIX, namespace));
            if (client.checkExists().forPath(zkPath) == null) {
                String path = client.create().creatingParentContainersIfNeeded().forPath(zkPath);
                if (Strings.isNullOrEmpty(path)) {
                    throw new StateManagerError(String.format("Error creating ZK base path. [path=%s]", basePath()));
                }
                agentTxState = new NameNodeTxState();
                agentTxState.setNamespace(namespace);
                agentTxState.setLastTxId(0);
                agentTxState.setUpdatedTime(0);

                String json = mapper.writeValueAsString(agentTxState);
                client.setData().forPath(zkPath, json.getBytes(StandardCharsets.UTF_8));
            } else {
                byte[] data = client.getData().forPath(zkPath);
                if (data == null || data.length <= 0) {
                    throw new StateManagerError(String.format("ZooKeeper state data corrupted. [path=%s]", zkPath));
                }
                String json = new String(data);
                agentTxState = mapper.readValue(json, NameNodeTxState.class);
                if (agentTxState.getNamespace().compareTo(namespace) != 0) {
                    throw new StateManagerError(String.format("Invalid state data: namespace mismatch. [expected=%s][actual=%s]", namespace, agentTxState.getNamespace()));
                }
            }
            return this;
        } catch (Exception ex) {
            throw new StateManagerError(ex);
        }
    }

    public NameNodeTxState setup(long txId) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        Preconditions.checkArgument(txId > agentTxState.getLastTxId());

        synchronized (this) {
            try {
                CuratorFramework client = connection().client();

                agentTxState.setLastTxId(txId);
                agentTxState.setUpdatedTime(System.currentTimeMillis());
                agentTxState.setCurrentEditsLogFile("");

                String json = mapper.writeValueAsString(agentTxState);
                client.setData().forPath(zkPath, json.getBytes(StandardCharsets.UTF_8));

                return agentTxState;
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public NameNodeTxState update(long txId, @NonNull String currentEditsLog) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        Preconditions.checkArgument(txId > agentTxState.getLastTxId());

        synchronized (this) {
            try {
                CuratorFramework client = connection().client();

                agentTxState.setLastTxId(txId);
                agentTxState.setUpdatedTime(System.currentTimeMillis());
                agentTxState.setCurrentEditsLogFile(currentEditsLog);

                String json = mapper.writeValueAsString(agentTxState);
                client.setData().forPath(zkPath, json.getBytes(StandardCharsets.UTF_8));

                return agentTxState;
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public NameNodeTxState update(@NonNull String currentFSImageFile) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());

        synchronized (this) {
            try {
                CuratorFramework client = connection().client();

                agentTxState.setUpdatedTime(System.currentTimeMillis());
                agentTxState.setCurrentFSImageFile(currentFSImageFile);
                String json = mapper.writeValueAsString(agentTxState);
                client.setData().forPath(zkPath, json.getBytes(StandardCharsets.UTF_8));

                return agentTxState;
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public NameNodeTxState readState() throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                byte[] data = client.getData().forPath(zkPath);
                if (data != null && data.length > 0) {
                    String json = new String(data, StandardCharsets.UTF_8);
                    return mapper.readValue(json, NameNodeTxState.class);
                }
                throw new StateManagerError(String.format("NameNode State not found. [path=%s]", zkPath));
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public Heartbeat heartbeat(@NonNull String name, @NonNull NameNodeAgentState.AgentState state) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                String path = PathUtils.formatZkPath(String.format("%s/%s", zkPath, Constants.ZK_PATH_HEARTBEAT));
                if (client.checkExists().forPath(path) == null) {
                    path = client.create().creatingParentContainersIfNeeded().forPath(path);
                    if (Strings.isNullOrEmpty(path)) {
                        throw new StateManagerError(String.format("Error creating ZK base path. [path=%s]", basePath()));
                    }
                }
                Heartbeat heartbeat = new Heartbeat();
                heartbeat.setName(name);
                heartbeat.setType(state.getClass().getCanonicalName());
                heartbeat.setState(state.state().name());
                if (state.hasError()) {
                    heartbeat.setError(state.error());
                }
                heartbeat.setTimestamp(System.currentTimeMillis());

                String json = mapper.writeValueAsString(heartbeat);
                client.setData().forPath(path, json.getBytes(StandardCharsets.UTF_8));

                return heartbeat;
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public Heartbeat heartbeat(@NonNull String name) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());

        try {
            CuratorFramework client = connection().client();
            String path = PathUtils.formatZkPath(String.format("%s/%s", zkPath, Constants.ZK_PATH_HEARTBEAT));
            if (client.checkExists().forPath(path) != null) {
                byte[] data = client.getData().forPath(path);
                if (data != null && data.length > 0) {
                    String json = new String(data, StandardCharsets.UTF_8);
                    return mapper.readValue(json, Heartbeat.class);
                }
            }
            return null;
        } catch (Exception ex) {
            throw new StateManagerError(ex);
        }
    }

    public DFSFileState create(@NonNull String path,
                               long inodeId,
                               long createdTime,
                               long blockSize,
                               long txId) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();

                String zp = getFilePath(path);
                DFSFileState fs = null;
                if (client.checkExists().forPath(zp) != null) {
                    fs = get(path);
                    if (!fs.isDeleted()) {
                        throw new IOException("Path already exists.");
                    } else {
                        client.delete().forPath(zp);
                    }
                }
                fs = new DFSFileState();

                fs.setId(inodeId);
                fs.setZkPath(zp);
                fs.setHdfsFilePath(path);
                fs.setCreatedTime(createdTime);
                fs.setUpdatedTime(createdTime);
                fs.setBlockSize(blockSize);
                fs.setDeleted(false);
                fs.setTimestamp(System.currentTimeMillis());
                fs.setLastTnxId(txId);

                byte[] data = JSONUtils.asBytes(fs, DFSFileState.class);
                client.create().creatingParentContainersIfNeeded().forPath(zp, data);
                return fs;
            } catch (Exception ex) {
                throw new StateManagerError(String.format("Error creating new file entry. [path=%s]", path));
            }
        }
    }

    public DFSFileState addOrUpdateBlock(@NonNull String path,
                                         long blockId,
                                         long updatedTime,
                                         long dataSize,
                                         long txId) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                DFSFileState fs = get(path);
                if (fs == null) {
                    throw new StateManagerError(String.format("File state not found. [path=%s]", path));
                }
                fs.setLastTnxId(txId);
                fs.setUpdatedTime(updatedTime);
                fs.setTimestamp(System.currentTimeMillis());
                long prevDataSize = 0;
                DFSBlockState bs = fs.get(blockId);
                if (bs == null) {
                    bs = new DFSBlockState();
                    bs.setBlockId(blockId);
                    bs.setCreatedTime(updatedTime);
                    bs.setBlockSize(fs.getBlockSize());
                    fs.add(bs);
                } else {
                    prevDataSize = bs.getDataSize();
                }
                bs.setUpdatedTime(updatedTime);
                bs.setLastTnxId(txId);
                bs.setDataSize(dataSize);

                BlockTnxDelta bd = new BlockTnxDelta();
                bd.setTnxId(txId);
                long soff = (prevDataSize > 0 ? prevDataSize - 1 : 0);
                long eoff = (dataSize > 0 ? dataSize - 1 : 0);
                bd.setStartOffset(soff);
                bd.setEndOffset(eoff);
                bd.setTimestamp(updatedTime);
                bs.add(bd);

                long ds = fs.getDataSize() + (dataSize - prevDataSize);
                fs.setDataSize(ds);

                return update(fs);
            } catch (Exception ex) {
                throw new StateManagerError(String.format("Error reading file entry. [path=%s]", path));
            }
        }
    }

    public DFSFileState update(@NonNull DFSFileState fileState) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        Preconditions.checkArgument(!Strings.isNullOrEmpty(fileState.getHdfsFilePath()));
        Preconditions.checkArgument(!Strings.isNullOrEmpty(fileState.getZkPath()));
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                String path = fileState.getZkPath();
                if (client.checkExists().forPath(path) == null) {
                    throw new StateManagerError(String.format("File record not found. [path=%s]", fileState.getHdfsFilePath()));
                }
                fileState.setTimestamp(System.currentTimeMillis());
                String json = mapper.writeValueAsString(fileState);
                client.setData().forPath(path, json.getBytes(StandardCharsets.UTF_8));

                return fileState;
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public DFSFileState compact(@NonNull String hdfsPath, long age) throws StateManagerError {
        synchronized (this) {
            DFSFileState fileState = get(hdfsPath);
            if (fileState == null) {
                throw new StateManagerError(String.format("File not found. [path=%s]", hdfsPath));
            }
            if (fileState.hasBlocks()) {
                for (DFSBlockState blockState : fileState.getBlocks()) {
                    if (blockState.hasTransactions()) {
                        List<BlockTnxDelta> array = new ArrayList<>();
                        long ts = System.currentTimeMillis() - age;
                        for (BlockTnxDelta tnx : blockState.getTransactions()) {
                            if (tnx.getTimestamp() > ts) {
                                array.add(tnx);
                            }
                        }
                        blockState.setTransactions(array);
                    }
                }
            }
            return update(fileState);
        }
    }

    public DFSFileState delete(@NonNull String hdfsPath) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        Preconditions.checkArgument(!Strings.isNullOrEmpty(hdfsPath));
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                DFSFileState fileState = get(hdfsPath);
                if (fileState == null) {
                    throw new StateManagerError(String.format("File state not found. [path=%s]", hdfsPath));
                }
                client.delete().deletingChildrenIfNeeded().forPath(fileState.getZkPath());

                return fileState;
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public DFSFileState markDeleted(@NonNull String hdfsPath) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        Preconditions.checkArgument(!Strings.isNullOrEmpty(hdfsPath));
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                DFSFileState fstate = get(hdfsPath);
                if (fstate == null) {
                    throw new StateManagerError(String.format("File record data is NULL. [path=%s]", hdfsPath));
                }
                fstate.setDeleted(true);

                return update(fstate);
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public DFSFileState get(@NonNull String hdfsPath) throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        Preconditions.checkArgument(!Strings.isNullOrEmpty(hdfsPath));

        try {
            CuratorFramework client = connection().client();
            String path = getFilePath(hdfsPath);
            if (client.checkExists().forPath(path) == null) {
                throw new StateManagerError(String.format("File record already exists. [path=%s]", hdfsPath));
            }
            byte[] data = client.getData().forPath(path);
            if (data != null && data.length > 0) {
                String json = new String(data, StandardCharsets.UTF_8);
                return mapper.readValue(json, DFSFileState.class);
            }
            return null;
        } catch (Exception ex) {
            throw new StateManagerError(ex);
        }
    }

    public void deleteAll() throws StateManagerError {
        Preconditions.checkNotNull(connection);
        Preconditions.checkState(connection.isConnected());
        synchronized (this) {
            try {
                CuratorFramework client = connection().client();
                String path = getFilePath(null);
                if (client.checkExists().forPath(path) != null) {
                    client.delete().deletingChildrenIfNeeded().forPath(path);
                }
            } catch (Exception ex) {
                throw new StateManagerError(ex);
            }
        }
    }

    public String getFilePath(String hdfsPath) {
        if (Strings.isNullOrEmpty(hdfsPath)) {
            return PathUtils.formatZkPath(String.format("%s/%s", zkPath, Constants.ZK_PATH_FILES));
        } else {
            return PathUtils.formatZkPath(String.format("%s/%s/%s", zkPath, Constants.ZK_PATH_FILES, hdfsPath));
        }
    }

    public String basePath() {
        return config().basePath();
    }

    @Getter
    @Accessors(fluent = true)
    public static class ZkStateManagerConfig extends DomainManager.DomainManagerConfig {

        private static final String __CONFIG_PATH = "state.manager";

        private String basePath;
        private String zkConnection;

        public ZkStateManagerConfig(@NonNull HierarchicalConfiguration<ImmutableNode> config) {
            super(config, __CONFIG_PATH);
        }
    }
}
